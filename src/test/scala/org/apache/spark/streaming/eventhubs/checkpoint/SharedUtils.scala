/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.eventhubs.checkpoint

import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.eventhubs.EventHubDirectDStream

private[checkpoint] trait SharedUtils {

  val appName = "dummyapp"
  val streamId = 0
  val nameSpace = "eventhubs"

  var fs: FileSystem = _
  var progressRootPath: Path = _
  var progressListner: ProgressTrackingListener = _
  var ssc: StreamingContext = _
  var progressTracker: ProgressTracker = _

  protected def createDirectStreams(
      ssc: StreamingContext,
      eventHubNamespace: String,
      checkpointDir: String,
      eventParams: Predef.Map[String, Predef.Map[String, String]]): EventHubDirectDStream = {
    ssc.addStreamingListener(new ProgressTrackingListener(checkpointDir, ssc))
    val newStream = new EventHubDirectDStream(ssc, eventHubNamespace, checkpointDir, eventParams)
    newStream
  }
}
