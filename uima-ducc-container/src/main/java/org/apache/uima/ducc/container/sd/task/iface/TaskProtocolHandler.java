/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/

package org.apache.uima.ducc.container.sd.task.iface;

import java.util.Properties;

import org.apache.uima.ducc.container.net.iface.IMetaCasTransaction;
import org.apache.uima.ducc.container.sd.iface.Lifecycle;
import org.apache.uima.ducc.container.sd.task.error.TaskProtocolException;

public interface TaskProtocolHandler extends Lifecycle {
	 public String  initialize(Properties props) throws TaskProtocolException;

     // The JP/Service sends IMetaCasTransaction object which includes  
     // the protocol state: GET, ACK, END
     public void handle(IMetaCasTransaction wi) throws TaskProtocolException;

}
