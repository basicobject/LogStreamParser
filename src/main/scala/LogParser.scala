import java.nio.file.{FileSystem, FileSystems, Paths}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.file.scaladsl.FileTailSource
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import io.circe._
import io.circe.parser._

object LogParser extends App {
  implicit val as: ActorSystem = ActorSystem("log-file-parser")

  val path = "/Users/deepakkumar/Downloads/logs1.json"
  val outputFile = "output-" + path.split("/").last + ".txt"

  val fs = FileSystems.getDefault
  val lines: Source[String, NotUsed] = FileTailSource
    .lines(
      path = fs.getPath(path),
      maxLineSize = 8192,
      pollingInterval = 500.millis
    )
    .idleTimeout(5.seconds)
    .recoverWithRetries(0, {
      case _: TimeoutException => Source.empty
    })

  def parseLine(line: String): ByteString = {
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
            case Left(err) => None
          }
      }
    }

    parseJson match {
      case Some(value) => ByteString(value + "\n")
      case None        => ByteString.empty
    }
  }

  lines
    .map(parseLine)
    .idleTimeout(5.seconds)
    .recoverWithRetries(0, {
      case _: TimeoutException => Source.empty
    })
    .runWith(FileIO.toPath(Paths.get(outputFile)))
    .andThen {
      case _ => as.terminate()
    }(as.dispatcher)
}
