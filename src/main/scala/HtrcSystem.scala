
package htrc.agent

// The place for global state.

import scala.collection.immutable.HashMap
import akka.agent.Agent
import scala.concurrent.stm._
import akka.actor.{ ActorSystem, ActorRef, Props }
import java.util.UUID
import scala.collection.mutable.{ HashMap => MHashMap }

object HtrcSystem {

  // the actor system hosting everything
  val system = ActorSystem("htrc")

  // make the system available implicitly
  implicit val htrcSystem = system

  // System Actors 
  //   * These need to be replaced with proper path-based lookups /
  //     routers / supervised.
  
  val agentBuilder = system.actorOf(Props(new AgentBuilder), name = "agentBuilder")

  val jobCreator = system.actorOf(Props(new JobCreator), name = "jobCreator")

  val localMachineResource = system.actorOf(Props(new LocalMachineResource), 
                                                  name = "localMachineResource")

}

// our global store of what agents exist
// includes operations to atomically add, remove, or lookup agents

object HtrcAgents {

  // make the actor system implicitly available
  import HtrcSystem._

  // the global store of what agents exist
  val agents = Agent(new HashMap[HtrcUser, ActorRef])

  def addAgent(user: HtrcUser, ref: ActorRef) {
    atomic { txn =>
      agents send { agents.get + (user -> ref) }
    }
  }

  def removeAgent(user: HtrcUser) {
    atomic { txn =>
      agents send { agents.get - user }
    }
  }

  def lookupAgent(user: HtrcUser): Option[ActorRef] = {
    agents.get.get(user)
  }

}

// Utility functions that don't have a home.

object HtrcUtils {

  def newJobId: String =
    UUID.randomUUID.toString

}

// The global store of configuration information.

object HtrcConfig {

  val systemVariables = new MHashMap[String,String]
  systemVariables += ("answer" -> "120")

}
