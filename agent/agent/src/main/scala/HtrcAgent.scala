
package htrcagent

import akka.actor.{ Props, Actor, ActorRef }
import akka.util.Timeout
import akka.util.duration._
import akka.actor.Actor._
import scala.collection.mutable.HashMap
import akka.pattern.{ ask, pipe }
import akka.dispatch.{ Promise, Future }

class HtrcAgent(credentials: HtrcCredentials) extends Actor {

  import context._

  // timeout for ? (ask)
  implicit val timeout = Timeout(5 seconds)

  val algorithms = new HashMap[String,Future[ActorRef]]

  def receive = {

    case ListAvailibleAlgorithms => 
      val f = (actorFor("/user/registryActor") ? ListAvailibleAlgorithms) 
      f.mapTo[List[String]].map { algs =>
        // this is where user specific query filtering can happen
        <availibleAlgorithms>
          {for(a <- algs) yield <algorithm>{a}</algorithm>}
        </availibleAlgorithms>
      } pipeTo sender

    case ListAvailibleCollections => 
      val f = (actorFor("/user/registryActor") ? ListAvailibleCollections)
      f.mapTo[List[String]].map { cols =>
        <collections>
          {for(c <- cols) yield <collection>{c}</collection>}
        </collections>
      } pipeTo sender
      
    case msg @ PollAlg(algId) => 

      val dest = sender
      algorithms(algId).map { alg =>
        (alg ? msg).mapTo[AlgorithmStatus].map { status =>
          status.renderXml
        } pipeTo dest
      } 

    case msg @ RunAlgorithm(algName, colName, args) => 
      val algId = newAlgId
      val nodeAllocator = system.actorFor("user/nodeAllocator")
      val dest = sender
   
      val child = (nodeAllocator ? ChildRequest(msg, algId)).mapTo[ActorRef]
      algorithms += (algId -> child)
 
      child.map { child =>
        (child ? PollAlg(algId)).mapTo[AlgorithmStatus].map { status =>
          status.renderXml
        } pipeTo dest
      }

    case msg @ AlgStdout(algId) =>
       val dest = sender
       algorithms(algId).map { alg =>
        (alg ? msg).mapTo[AlgorithmResult].map { res =>
          <algorithm>
            <id>{algId}</id>
            {res.renderXml}
          </algorithm> 
         } pipeTo dest
       }

    case msg @ AlgStderr(algId) =>
      val dest = sender
      algorithms(algId).map { alg =>
      (alg ? msg).mapTo[AlgorithmResult].map { res =>
        <algorithm>
          <id>{algId}</id>
          {res.renderXml}
        </algorithm> } pipeTo dest
                           }

    case AlgFile(algId, filename) =>
      sender ! <algFile>{filename}</algFile>

    case ListAgentAlgorithms =>
      val dest = sender
      val f:List[Future[AlgorithmStatus]] = algorithms.toList map { case (id,ref) =>
                  val res = ref.map(_ ? PollAlg(id)).mapTo[AlgorithmStatus] 
                                                                   println("post f generation")
                                                                   res
}
    
      Future.sequence[AlgorithmStatus,List](f).mapTo[List[AlgorithmStatus]] map { li =>
        println("made it to xml generation")
        <algorithms>
         {for (a <- li) yield {a.renderXml}}
        </algorithms>
      } pipeTo dest

  }

  // a means to create unique algorithm ids
  var count = 0
  def newAlgId: String = {
    count += 1
    "algId_" + count.toString + "_" + self.path.name
  }

}
