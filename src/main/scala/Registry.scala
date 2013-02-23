
package htrc.agent

// A proxy for the WSO2 Registry metadata store. Currently a stub, but
// will eventually (very shortly) use a RESTful API to make these
// requests.

import akka.actor.{ Actor, Props, ActorRef }
import akka.util.Timeout
import scala.concurrent.duration._
import akka.event.Logging
import java.io.File
import java.io.PrintWriter
import scala.sys.process.{ Process => SProcess }
import scala.sys.process.{ ProcessLogger => SProcessLogger }
import scala.sys.process.{ ProcessBuilder => SProcessBuilder }

class Registry extends Actor {

  // setup

  import context._
  implicit val timeout = Timeout(30 seconds)
  val log = Logging(context.system, this)

  // logic code

  // For now the "registry" is a directory with a few files in
  // it. I'll copy from that directory to the working dir.

  def cp(source: String, dest: String): Any = {
    val cmd = "cp %s %s".format(source,dest)
    val proc = SProcess(cmd)
    val exitValue = proc.!
    if(exitValue == 0)
      RegistryOk
    else
      RegistryError("could not copy resource: " + source)
  }

  val storage = "registry_dir"
  
  // receive loop

  val behavior: PartialFunction[Any,Unit] = {
    case m: RegistryMessage =>
      m match {
        case WriteFile(path, name, dir) =>
          log.info("writing file")
          sender ! cp(storage + "/" + path, dir + "/" + name)
        case WriteCollection(name, dir) =>
          log.info("writing collection")
          sender ! cp(storage + "/" + name, dir + "/" + name)
      }
  }

  val unknown: PartialFunction[Any,Unit] = {
    case m =>
      log.error("registry received unknown message")
  }

  def receive = behavior orElse unknown

}
