/*
 * Copyright 2014 Claude Mamo
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package actors

import common.{Message, Registry}
import Registry.PropertyConstants
import models.{Status, Zookeeper}
import akka.actor.{ActorRef, Actor}
import com.twitter.zk._
import com.twitter.util.JavaTimer
import com.twitter.conversions.time._
import play.api.libs.concurrent.Akka
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import Message.ConnectNotification
import akka.actor.Terminated
import scala.Some
import org.apache.zookeeper.Watcher.Event.KeeperState
import scala.concurrent.Await
import common.Util

class ConnectionManager() extends Actor {

  val router = Akka.system.actorSelection("akka://application/user/router")

  override def preStart() {
    for (zookeeper <- models.Zookeeper.findAll) {
      router ! Message.Connect(zookeeper)
    }
  }

  override def receive: Actor.Receive = {
    case connectMessage: Message.Connect => connect(connectMessage.zookeeper)
    case connectNotification: Message.ConnectNotification => Zookeeper.upsert(connectNotification.zookeeper)
    case disconnectMessage: Message.Disconnect => disconnect(disconnectMessage.zookeeper)
    case Terminated => terminate()
  }

  private def connect(zk: Zookeeper) {
    models.Zookeeper.findById(zk.id) match {
      case Some(s) => if (s.statusId == Status.Deleted.id) return
      case _ => return
    }

    val zkClient = getZkClient(zk, lookupZookeeperConnections())

    val onSessionEvent: PartialFunction[StateEvent, Unit] = {
      case s: StateEvent if s.state == KeeperState.SyncConnected =>
        router ! ConnectNotification(Zookeeper(zk.name, zk.host, zk.port, zk.groupId, Status.Connected.id))
      case s: StateEvent if s.state == KeeperState.Disconnected =>
        router ! ConnectNotification(Zookeeper(zk.name, zk.host, zk.port, zk.groupId, Status.Connecting.id))
      case s: StateEvent if s.state == KeeperState.Expired =>
        router ! ConnectNotification(Zookeeper(zk.name, zk.host, zk.port, zk.groupId, Status.Connecting.id))
    }

    zkClient.onSessionEvent(onSessionEvent)

    zkClient().onFailure(_ => {
      router ! ConnectNotification(Zookeeper(zk.name, zk.host, zk.port, zk.groupId, Status.Connecting.id))
      Akka.system.scheduler.scheduleOnce(
        Duration.create(5, TimeUnit.SECONDS), self, Message.Connect(zk)
      )
    })
  }

  private def terminate() {
    shutdownConnections()
    Zookeeper.update(Zookeeper.findAll.map(z => Zookeeper(z.id, z.host, z.port, z.groupId, Status.Disconnected.id)))
  }

  private def shutdownConnections() {
    Registry.lookupObject(PropertyConstants.ZookeeperConnections) match {
      case Some(s: Map[_, _]) =>
        s.asInstanceOf[Map[String, ZkClient]].map(z => Await.result(Util.twitterToScalaFuture(z._2.release()), Duration.Inf))
      case _ =>
    }
  }

  private def getZkClient(zk: Zookeeper, zkConnections: Map[String, ZkClient]): ZkClient = {
    zkConnections.filterKeys(_ == zk.id) match {
      case zk if zk.size > 0 => zk.head._2
      case _ =>
        val zkClient = ZkClient(zk.toString, 6000 milliseconds, 6000 milliseconds)(new JavaTimer)
        Registry.registerObject(PropertyConstants.ZookeeperConnections, Map(zk.id -> zkClient) ++ zkConnections)
        zkClient
    }
  }

  private def lookupZookeeperConnections(): Map[String, ZkClient] = {
    Registry.lookupObject(PropertyConstants.ZookeeperConnections) match {
      case Some(zkConnections: Map[_, _]) => zkConnections.asInstanceOf[Map[String, ZkClient]]
      case _ => Registry.registerObject(PropertyConstants.ZookeeperConnections, Map[String, ZkClient]())
    }
  }

  private def disconnect(zk: Zookeeper) {
    lookupZookeeperConnections().get(zk.id) match {
      case Some(zkClient) =>
        zkClient.release()
        Registry.registerObject(PropertyConstants.ZookeeperConnections, lookupZookeeperConnections().filterKeys(_ != zk.id))
        Zookeeper.delete(models.Zookeeper.findById(zk.id).get)
      case _ =>
    }
  }

}