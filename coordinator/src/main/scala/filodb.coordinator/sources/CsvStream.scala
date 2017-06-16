package filodb.coordinator.sources

import com.opencsv.CSVReader
import com.typesafe.config.Config
import com.typesafe.scalalogging.slf4j.StrictLogging
import monix.reactive.Observable
import net.ceedubs.ficus.Ficus._
import org.velvia.filo.ArrayStringRowReader

import filodb.coordinator.{Ingest, IngestStreamFactory}
import filodb.core.memstore.IngestRecord
import filodb.core.metadata.{Dataset, RichProjection}

object CsvStream extends StrictLogging {
  // Number of lines to read and send at a time
  val BatchSize = 100

  final case class CsvStreamSettings(header: Boolean = true,
                                     batchSize: Int = BatchSize,
                                     separatorChar: Char = ',')

  def getHeaderColumns(csvStream: java.io.Reader,
                       separatorChar: Char = ','): Seq[String] = {
    val reader = new CSVReader(csvStream, separatorChar)
    reader.readNext.toSeq
  }

  def getHeaderColumns(csvPath: String): Seq[String] = {
    val fileReader = new java.io.FileReader(csvPath)
    getHeaderColumns(fileReader)
  }
}

/**
 * Config for CSV ingestion:
 * {{{
 *   file = "/path/to/file.csv"
 *   header = true
 *   batch-size = 100
 *   # separator-char = ","
 * }}}
 * Instead of file one can put "resource"
 *
 * If the CSV has a header, you need to set header=true.  Since a projection is already required,
 * you need to separately parse the header another time to get the list of input columns first before
 * setting up the ingestion.
 */
class CsvStreamFactory extends IngestStreamFactory {
  import CsvStream._
  import collection.JavaConverters._

  def create(config: Config, projection: RichProjection): Ingest.IngestStream = {
    val settings = CsvStreamSettings(config.getBoolean("header"),
                     config.as[Option[Int]]("batch-size").getOrElse(BatchSize),
                     config.as[Option[String]]("separator-char").getOrElse(",").charAt(0))
    val reader = config.as[Option[String]]("file").map { filePath =>
                   new java.io.FileReader(filePath)
                 }.getOrElse {
                   new java.io.InputStreamReader(getClass.getResourceAsStream(config.getString("resource")))
                 }
    val csvReader = new CSVReader(reader, settings.separatorChar)

    if (settings.header) csvReader.readNext

    val batchIterator = csvReader.iterator.asScala
                          .zipWithIndex
                          .map { case (tokens, idx) =>
                            IngestRecord(projection, ArrayStringRowReader(tokens), idx)
                          }.grouped(settings.batchSize)
    Observable.fromIterator(batchIterator)
  }
}
