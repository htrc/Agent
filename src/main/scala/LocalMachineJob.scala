
/*
#
# Copyright 2013 The Trustees of Indiana University
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
*/


package htrc.agent

// An actor that supervises and runs the local machine job type.

import akka.actor.{ Actor, Props, ActorRef, PoisonPill }
import akka.util.Timeout
import scala.concurrent.duration._
import akka.event.Logging
import java.io._

class LocalMachineJob(user: HtrcUser, inputs: JobInputs, id: JobId) extends Actor {

  import HtrcUtils._

  // actor configuration
  import context._
  implicit val timeout = Timeout(30 seconds)
  val log = Logging(context.system, this)

  log.info("LOCAL_MACHINE_JOB_ACTOR_STARTED\t{}\t{}\tJOB_ID: {}",
           user.name, user.ip, id)

  // The mutable state representing current status.
  val stdout = new StringBuilder
  val stderr = new StringBuilder
  var status: JobStatus = Queued(inputs, id)

  // As a local machine shell job, we just start our child directly.
  var child: ActorRef = null
  def makeChild = actorOf(Props(new OdinTask(user, inputs, id)))  

  // mutable state party time
  var results: List[JobResult] = Nil
  var stdoutResult: Stdout = null
  var stderrResult: Stderr = null

  val behavior: PartialFunction[Any,Unit] = {
    case m: JobMessage => {
      m match {
        case Result(res) =>
          results = res :: results
        case SaveJob(id) =>
          sender ! <error>Job saving not implemented</error>
        case DeleteJob(id) =>
          sender ! <success>deleted job: {id}</success>
          self ! PoisonPill
        case JobStatusRequest(id) =>
          log.info("JOB_ACTOR_STATUS_REQUEST\t{}\t{}\tJOB_ID: {}\tSTATUS: {}",
                   user.name, user.ip, id, status)
          sender ! status.renderXml
        case StatusUpdate(newStatus) =>
          log.info("JOB_ACTOR_STATUS_UPDATE\t{}\t{}\tJOB_ID: {}\tSTATUS: {}",
                   user.name, user.ip, id, newStatus)
          newStatus match {
            case InternalQueued =>
              status = Queued(inputs, id)
            case InternalStaging =>
              status = Staging(inputs, id)
            case InternalRunning =>
              status = Running(inputs, id)
            case InternalFinished =>
              val stdoutUrl = writeFile(stdout.toString, "stdout.txt", user, id)
              val stderrUrl = writeFile(stderr.toString, "stderr.txt", user, id)
              stdoutResult = Stdout(stdoutUrl)
              stderrResult = Stderr(stderrUrl)
              results = stdoutResult :: stderrResult :: results
              status = Finished(inputs, id, results)
            case InternalCrashed =>
              val stdoutUrl = writeFile(stdout.toString, "stdout.txt", user, id)
              val stderrUrl = writeFile(stderr.toString, "stderr.txt", user, id)
              stdoutResult = Stdout(stdoutUrl)
              stderrResult = Stderr(stderrUrl)
              results = stdoutResult :: stderrResult :: results
              status = Crashed(inputs, id, results)
          }
        case StdoutChunk(str) =>
          stdout.append(str + "\n")
        case StderrChunk(str) =>
          stderr.append(str + "\n")
        case JobOutputRequest(id, "stdout") =>
          sender ! stdoutResult.renderXml
        case JobOutputRequest(id, "stderr") =>
          sender ! stderrResult.renderXml
        case JobOutputRequest(id, outputType) =>
          sender ! "unrecognized output type: " + outputType
        case RunJob =>
          log.info("launching job")
          child = makeChild
      }
    }
  }

  val unknown: PartialFunction[Any,Unit] = {
    case m =>
      log.error("job supervisor")
  }

  def receive = behavior orElse unknown

}
