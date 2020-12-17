import java.nio.file.{FileVisitOption, Files, Path, Paths}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Flow, Framing, Source}
import akka.util.ByteString
import io.circe.parser._
import org.apache.datasketches.frequencies.{ErrorType, ItemsSketch}
import org.apache.datasketches.hll.HllSketch

import scala.concurrent.{ExecutionContext, Future}

object LogParser extends App {
  implicit val as: ActorSystem = ActorSystem("log-file-parser")
  final val parallelism = Runtime.getRuntime.availableProcessors() / 2
  val logRootDir = "/Users/deepakkumar/Downloads/logs_back"
  val outputFile = "output.txt"

  def parseLine(
    line: String
  )(implicit ec: ExecutionContext): Future[ByteString] = Future {
    def parseJson: Option[String] = {
      parse(line) match {
        case Left(err) =>
          println("Invalid json object")
          None
        case Right(json) =>
          val message = json.hcursor
            .downField("jsonPayload")
            .downField("message")
            .as[String]

          message match {
            case Right(text) =>
              if (text.startsWith("c.t.a.m.MediationService:")) {
                Some(text.split("id=").last)
              } else None
            case Left(_) => None
          }
      }
    }

    parseJson match {
      case Some(value) => ByteString(value + "\n")
      case None        => ByteString.empty
    }
  }

  val logFiles: Source[Path, NotUsed] = Directory.walk(
    Paths.get(logRootDir),
    Some(1),
    Seq(FileVisitOption.FOLLOW_LINKS)
  )

  val fileFilter: Flow[Path, Path, NotUsed] =
    Flow[Path].filter(path => Files.isRegularFile(path))

  val delimiter = Framing.delimiter(
    ByteString("\n"),
    maximumFrameLength = 8192,
    allowTruncation = true
  )

  val startedAt = System.currentTimeMillis()

  val freqSketch = new ItemsSketch[String](1048576)
  val hllSketch = new HllSketch()

  logFiles
    .via(fileFilter)
    .flatMapConcat(FileIO.fromPath(_))
    .via(delimiter)
    .mapAsync(parallelism)(line => Future.successful(line.utf8String))
    .mapAsync(parallelism)(line => parseLine(line)(as.dispatcher))
    .map { app =>
      freqSketch.update(app.utf8String)
      hllSketch.update(app.utf8String)
      app
    }
    .runWith(FileIO.toPath(Paths.get(outputFile)))
    .andThen {
      case _ =>
        val endAt = System.currentTimeMillis()
        println(s"Time taken: ${endAt - startedAt} millis")
        val rows =
          freqSketch
            .getFrequentItems(10000L, ErrorType.NO_FALSE_POSITIVES)
            .toSeq
        rows.foreach(row => println(row.getItem))
        println(s"Unique apps ${hllSketch.getEstimate}")
        as.terminate()
    }(as.dispatcher)
}
