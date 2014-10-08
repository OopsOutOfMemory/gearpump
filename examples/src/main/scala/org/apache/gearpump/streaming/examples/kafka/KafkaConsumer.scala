/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.streaming.examples.kafka

import kafka.api.{FetchRequestBuilder, TopicMetadataRequest}
import kafka.common.ErrorMapping._
import kafka.common.TopicAndPartition
import kafka.consumer.SimpleConsumer
import kafka.message.MessageAndOffset
import kafka.serializer.StringDecoder
import kafka.utils.{Utils, ZkUtils}
import org.I0Itec.zkclient.ZkClient
import org.slf4j.{LoggerFactory, Logger}

import scala.util.{Failure, Success, Try}

object KafkaConsumer {

  object Broker {
    def toString(brokers: List[Broker]) = brokers.mkString(",")
  }

  case class Broker(host: String, port: Int) {
    override def toString = s"${host}:${port}"
  }

  private val LOG: Logger = LoggerFactory.getLogger(classOf[KafkaSpout])
}

class KafkaConsumer(topicAndPartitions: Array[TopicAndPartition],
                    clientId: String, socketTimeout: Int,
                    receiveBufferSize: Int, fetchSize: Int,
                    zkClient: ZkClient)  {

  import org.apache.gearpump.streaming.examples.kafka.KafkaConsumer._

  private val brokers = {
    ZkUtils.getAllBrokersInCluster(zkClient).map(b => Broker(b.host, b.port)).toList
  }

  private val leaders: Map[TopicAndPartition, Broker] = topicAndPartitions.map(
    tp => (tp, findLeader(brokers, tp.topic, tp.partition).get)).toMap

  private val iterators: Map[TopicAndPartition, MessageIterator] = topicAndPartitions.map(
    tp => {
      val broker = leaders(tp)
      val host = broker.host
      val port = broker.port
      (tp, new MessageIterator(host, port, tp.topic, tp.partition, 0L,
        socketTimeout, receiveBufferSize, fetchSize, clientId))
    }).toMap

  private var partitionIndex = 0
  private val partitionNum = topicAndPartitions.length

  def start(): Unit = {

  }

  def nextMessage(): (TopicAndPartition, String) = {
    val tp = topicAndPartitions(partitionIndex)
    val iter = iterators(tp)
    partitionIndex = (partitionIndex + 1) % partitionNum
    if (iter.hasNext()) {
      (tp, iter.next())
    } else {
      null
    }
  }

  def getOffset(topicAndPartition: TopicAndPartition): Long = {
    iterators(topicAndPartition).getOffset()
  }

  def close(): Unit = {
    iterators.foreach(_._2.close())
  }

  private def findLeader(brokers: List[Broker], topic: String, partition: Int): Option[Broker] = brokers match {
    case Nil => throw new IllegalArgumentException("empty broker list")
    case Broker(host, port) :: Nil =>
      findLeader(host, port, topic, partition)
    case Broker(host, port) :: brokers =>
      Try(findLeader(host, port, topic, partition)) match {
        case Success(leader) => leader
        case Failure(e) => findLeader(brokers, topic, partition)
      }
  }

  private def findLeader(host: String, port: Int, topic: String, partition: Int): Option[Broker] = {
    val consumer = new SimpleConsumer(host, port, socketTimeout, receiveBufferSize, clientId)
    val request = new TopicMetadataRequest(TopicMetadataRequest.CurrentVersion, 0, clientId, List(topic))
    try {
      val response = consumer.send(request)
      val metaData = response.topicsMetadata(0)

      metaData.errorCode match {
        case NoError => metaData.partitionsMetadata
          .filter(_.partitionId == partition)(0).leader
          .map(l => Broker(l.host, l.port))
        case LeaderNotAvailableCode => None
        case error => throw exceptionFor(error)
      }
    } finally {
      consumer.close()
    }
  }
}

class MessageIterator(host: String,
                      port: Int,
                      topic: String,
                      partition: Int,
                      startOffset: Long,
                      soTimeout: Int,
                      bufferSize: Int,
                      fetchSize: Int,
                      clientId: String) {


  private val consumer = new SimpleConsumer(host, port, soTimeout, bufferSize, clientId)
  private val decoder = new StringDecoder()

  private var iter = iterator(startOffset)
  private var readMessages = 0L
  private var offset = startOffset
  private var nextOffset = offset

  def getOffset(): Long = {
    offset
  }

  def next(): String = {
    val mo = iter.next()
    readMessages += 1
    offset = mo.offset
    nextOffset = mo.nextOffset
    decoder.fromBytes(Utils.readBytes(mo.message.payload))
  }

  @annotation.tailrec
  final def hasNext(): Boolean = {
    if (iter.hasNext) {
      true
    } else if (0 == readMessages) {
      close()
      false
    } else {
      iter = iterator(nextOffset)
      readMessages = 0
      hasNext()
    }
  }

  def close(): Unit = {
    consumer.close()
  }

  private def iterator(offset: Long): Iterator[MessageAndOffset] = {
    val request = new FetchRequestBuilder()
      .clientId(clientId)
      .addFetch(topic, partition, offset, fetchSize)
      .build()

    val response = consumer.fetch(request)
    response.errorCode(topic, partition) match {
      case NoError => response.messageSet(topic, partition).iterator
      case error => throw exceptionFor(error)
    }
  }
}