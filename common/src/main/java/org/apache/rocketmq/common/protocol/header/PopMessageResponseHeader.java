/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * $Id: PullMessageResponseHeader.java 1835 2013-05-16 02:00:50Z vintagewang@apache.org $
 */
package org.apache.rocketmq.common.protocol.header;

import org.apache.rocketmq.remoting.CommandCustomHeader;
import org.apache.rocketmq.remoting.annotation.CFNotNull;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;

public class PopMessageResponseHeader implements CommandCustomHeader {


	@CFNotNull
    private Long popTime;
    @CFNotNull
    private Long invisibleTime;
    
    @CFNotNull
    private int reviveQid;   
    @CFNotNull
    private long msgNum;  

	@Override
    public void checkFields() throws RemotingCommandException {
    }
    public Long getPopTime() {
		return popTime;
	}
    public long getMsgNum() {
		return msgNum;
	}
    public void setMsgNum(long msgNum) {
		this.msgNum = msgNum;
	}
	public void setPopTime(Long popTime) {
		this.popTime = popTime;
	}

	public Long getInvisibleTime() {
		return invisibleTime;
	}

	public void setInvisibleTime(Long invisibleTime) {
		this.invisibleTime = invisibleTime;
	}
    public int getReviveQid() {
		return reviveQid;
	}
	public void setReviveQid(int reviveQid) {
		this.reviveQid = reviveQid;
	}
}
