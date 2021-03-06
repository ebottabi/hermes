/*
 * Copyright (C) 2016 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.hermes.actors

import java.util.concurrent.atomic.AtomicInteger
import java.util.{Date, UUID}

import akka.actor.{Actor, ActorLogging}
import akka.cluster.Cluster
import com.stratio.hermes.actors.WorkerSupervisorActor._
import com.stratio.hermes.constants.HermesConstants
import com.stratio.hermes.helpers.TwirlHelper
import com.stratio.hermes.kafka.KafkaClient
import com.stratio.hermes.utils.Hermes
import com.typesafe.config.Config
import org.apache.avro.Schema
import play.twirl.api.Txt
import tech.allegro.schema.json2avro.converter.JsonAvroConverter

/**
 * Actor that produces messages to kafka. It runs n threads depending of the number of processors of the node.
 * @param config with the cluster configuration.
 */
class WorkerSupervisorActor()(implicit config: Config)
  extends Actor with ActorLogging {

  val cluster = Cluster(context.system)
  val NumberOfMessagesToLog = 10
  val hermes = Hermes("EN")
  val template = TwirlHelper.template[(Hermes) => Txt](HermesConstants.TwirlTemplate, "templateTest")
  val converter = new JsonAvroConverter()
  val schema = new Schema.Parser().parse(HermesConstants.AvroSchema)
  val topic = config.getString("hermes.topic")

  UUID.randomUUID().toString

  override def receive: Receive = {
    case Start =>
      val count = new AtomicInteger(0)
      val initialTime = new Date().getTime
      startThread(1, count, initialTime)
      sender ! StartOK
  }

  protected def startThread(threadIndex: Int, count: AtomicInteger, time: Long): Unit = {
    val thread = new Thread(new Runnable {
      override def run(): Unit = {
        val kafkaClient = new KafkaClient[Object]
        val hermes = Hermes("ES")
        produce(kafkaClient, hermes, count, time, 1)
      }

      def produce(kafkaClient: KafkaClient[Object],
                  hermes: Hermes,
                  count: AtomicInteger,
                  time: Long,
                  index: Int): Unit = {

        val json = template.static(hermes).toString()
        val record = converter.convertToGenericDataRecord(json.getBytes("UTF-8"), schema)


        kafkaClient.send(topic, record)
        if(index % NumberOfMessagesToLog == 0) {
          log.info(s"Produced ${count.addAndGet(NumberOfMessagesToLog)} messages in thread-$threadIndex")
          Thread.sleep(3000L)
        }
        produce(kafkaClient, hermes, count, time, index + 1)
      }
    })

    thread.setName(s"thread-$threadIndex")
    thread.start
  }
}

object WorkerSupervisorActor {
  case object Start
  case object StartOK
}
