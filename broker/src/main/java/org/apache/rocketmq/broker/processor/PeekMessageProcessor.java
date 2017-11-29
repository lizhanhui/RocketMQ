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
package org.apache.rocketmq.broker.processor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.FileRegion;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.pagecache.ManyMessageTransfer;
import org.apache.rocketmq.common.KeyBuilder;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.help.FAQUrl;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.PeekMessageRequestHeader;
import org.apache.rocketmq.common.protocol.header.PopMessageResponseHeader;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.GetMessageResult;
import org.apache.rocketmq.store.GetMessageStatus;
import org.apache.rocketmq.store.SelectMappedBufferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeekMessageProcessor implements NettyRequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private final BrokerController brokerController;
    private Random random=new Random(System.currentTimeMillis());

    public PeekMessageProcessor(final BrokerController brokerController) {
        this.brokerController = brokerController;
    }

    @Override
    public RemotingCommand processRequest(final ChannelHandlerContext ctx, RemotingCommand request) throws RemotingCommandException {
        return this.processRequest(ctx.channel(), request, true);
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

    private RemotingCommand processRequest(final Channel channel, RemotingCommand request, boolean brokerAllowSuspend)
        throws RemotingCommandException {
        RemotingCommand response = RemotingCommand.createResponseCommand(PopMessageResponseHeader.class);
        final PeekMessageRequestHeader requestHeader =
            (PeekMessageRequestHeader) request.decodeCommandCustomHeader(PeekMessageRequestHeader.class);

        response.setOpaque(request.getOpaque());

        if (LOG.isDebugEnabled()) {
            LOG.debug("receive PeekMessage request command, {}", request);
        }

        if (!PermName.isReadable(this.brokerController.getBrokerConfig().getBrokerPermission())) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark(String.format("the broker[%s] peeking message is forbidden", this.brokerController.getBrokerConfig().getBrokerIP1()));
            return response;
        }

        TopicConfig topicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(requestHeader.getTopic());
        if (null == topicConfig) {
            LOG.error("The topic {} not exist, consumer: {} ", requestHeader.getTopic(), RemotingHelper.parseChannelRemoteAddr(channel));
            response.setCode(ResponseCode.TOPIC_NOT_EXIST);
            response.setRemark(String.format("topic[%s] not exist, apply first please! %s", requestHeader.getTopic(), FAQUrl.suggestTodo(FAQUrl.APPLY_TOPIC_URL)));
            return response;
        }

        if (!PermName.isReadable(topicConfig.getPerm())) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark("the topic[" + requestHeader.getTopic() + "] peeking message is forbidden");
            return response;
        }

        if (requestHeader.getQueueId() >= topicConfig.getReadQueueNums()) {
            String errorInfo = String.format("queueId[%d] is illegal, topic:[%s] topicConfig.readQueueNums:[%d] consumer:[%s]",
                    requestHeader.getQueueId(), requestHeader.getTopic(), topicConfig.getReadQueueNums(), channel.remoteAddress());
            LOG.warn(errorInfo);
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark(errorInfo);
            return response;
        }
		int randomQ=random.nextInt(100);
		int reviveQid=randomQ % this.brokerController.getBrokerConfig().getReviveQueueNum();
		GetMessageResult getMessageResult=new GetMessageResult();
		boolean needRetry=(randomQ % 5 == 0);
		long popTime=System.currentTimeMillis();
		if (needRetry) {
			TopicConfig retryTopicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(KeyBuilder.buildPopRetryTopic(requestHeader.getTopic(), requestHeader.getConsumerGroup()));
			if (retryTopicConfig != null) {
				for (int i = 0; i < retryTopicConfig.getReadQueueNums(); i++) {
					int queueId = (randomQ + i) % retryTopicConfig.getReadQueueNums();
					peekMsgFromQueue(true, getMessageResult, requestHeader, queueId, reviveQid, channel, popTime);
				}
			}
		}
		if (requestHeader.getQueueId() < 0) {
			// read all queue
			for (int i = 0; i < topicConfig.getReadQueueNums(); i++) {
				int queueId = (randomQ + i) % topicConfig.getReadQueueNums();
				peekMsgFromQueue(false, getMessageResult, requestHeader, queueId, reviveQid, channel,popTime);
			}
		}else {
			int queueId = requestHeader.getQueueId();
			peekMsgFromQueue(false, getMessageResult, requestHeader, queueId, reviveQid, channel,popTime);
		}
		// if not full , fetch retry again
		if (!needRetry && getMessageResult.getMessageMapedList().size() < requestHeader.getMaxMsgNums()) {
			TopicConfig retryTopicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(KeyBuilder.buildPopRetryTopic(requestHeader.getTopic(), requestHeader.getConsumerGroup()));
			if (retryTopicConfig != null) {
				for (int i = 0; i < retryTopicConfig.getReadQueueNums(); i++) {
					int queueId = (randomQ + i) % retryTopicConfig.getReadQueueNums();
					peekMsgFromQueue(true, getMessageResult, requestHeader, queueId , reviveQid, channel, popTime);
				}
			}
		}
		if (!getMessageResult.getMessageBufferList().isEmpty()) {
            response.setCode(ResponseCode.SUCCESS);
            getMessageResult.setStatus(GetMessageStatus.FOUND);
		}else{
            response.setCode(ResponseCode.PULL_NOT_FOUND);
            getMessageResult.setStatus(GetMessageStatus.NO_MESSAGE_IN_QUEUE);

		}

        response.setRemark(getMessageResult.getStatus().name());
        switch (response.getCode()) {
            case ResponseCode.SUCCESS:

                this.brokerController.getBrokerStatsManager().incGroupGetNums(requestHeader.getConsumerGroup(), requestHeader.getTopic(),
                    getMessageResult.getMessageCount());

                this.brokerController.getBrokerStatsManager().incGroupGetSize(requestHeader.getConsumerGroup(), requestHeader.getTopic(),
                    getMessageResult.getBufferTotalSize());

                this.brokerController.getBrokerStatsManager().incBrokerGetNums(getMessageResult.getMessageCount());
                if (this.brokerController.getBrokerConfig().isTransferMsgByHeap()) {
                    final long beginTimeMills = this.brokerController.getMessageStore().now();
                    final byte[] r = this.readGetMessageResult(getMessageResult, requestHeader.getConsumerGroup(), requestHeader.getTopic(), requestHeader.getQueueId());
                    this.brokerController.getBrokerStatsManager().incGroupGetLatency(requestHeader.getConsumerGroup(),
                        requestHeader.getTopic(), requestHeader.getQueueId(),
                        (int) (this.brokerController.getMessageStore().now() - beginTimeMills));
                    response.setBody(r);
                } else {
                	final GetMessageResult tmpGetMessageResult=getMessageResult;
                    try {
                        FileRegion fileRegion =
                            new ManyMessageTransfer(response.encodeHeader(getMessageResult.getBufferTotalSize()), getMessageResult);
                        channel.writeAndFlush(fileRegion).addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) throws Exception {
                            	tmpGetMessageResult.release();
                                if (!future.isSuccess()) {
                                    LOG.error("Fail to transfer messages from page cache to {}", channel.remoteAddress(), future.cause());
                                }
                            }
                        });
                    } catch (Throwable e) {
                        LOG.error("Error occurred when transferring messages from page cache", e);
                        getMessageResult.release();
                    }

                    response = null;
                }
                break;
            default:
                assert false;
        }
        return response;
}

	private void peekMsgFromQueue(boolean isRetry, GetMessageResult getMessageResult, PeekMessageRequestHeader requestHeader, int queueId, int reviveQid, Channel channel, long popTime) {
		String topic = isRetry ? KeyBuilder.buildPopRetryTopic(requestHeader.getTopic(), requestHeader.getConsumerGroup()) : requestHeader.getTopic();
		GetMessageResult getMessageTmpResult;
		if (getMessageResult.getMessageMapedList().size() >= requestHeader.getMaxMsgNums()) {
			return;
		}
		long offset = getPopOffset(topic, requestHeader.getConsumerGroup(), queueId);
		getMessageTmpResult = this.brokerController.getMessageStore().getMessage(requestHeader.getConsumerGroup(), topic, queueId, offset,
				requestHeader.getMaxMsgNums() - getMessageResult.getMessageMapedList().size(), null);
		// maybe store offset is not correct.
		if (GetMessageStatus.OFFSET_TOO_SMALL.equals(getMessageTmpResult.getStatus()) || GetMessageStatus.OFFSET_OVERFLOW_BADLY.equals(getMessageTmpResult.getStatus())) {
			offset = getMessageTmpResult.getNextBeginOffset();
			getMessageTmpResult = this.brokerController.getMessageStore().getMessage(requestHeader.getConsumerGroup(), topic, queueId, offset,
					requestHeader.getMaxMsgNums() - getMessageResult.getMessageMapedList().size(), null);
		}
		if (getMessageTmpResult != null) {
			for (SelectMappedBufferResult mapedBuffer : getMessageTmpResult.getMessageMapedList()) {
				getMessageResult.addMessage(mapedBuffer);
			}
		}
		return;
	}
	private long getPopOffset(String topic, String cid, int queueId) {
		long offset = this.brokerController.getConsumerOffsetManager().queryOffset(cid, topic, queueId);
		if (offset < 0) {
			offset = this.brokerController.getMessageStore().getMinOffsetInQueue(topic, queueId);
		}
		return offset;
	}
    private byte[] readGetMessageResult(final GetMessageResult getMessageResult, final String group, final String topic, final int queueId) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(getMessageResult.getBufferTotalSize());

        long storeTimestamp = 0;
        try {
            List<ByteBuffer> messageBufferList = getMessageResult.getMessageBufferList();
            for (ByteBuffer bb : messageBufferList) {

                byteBuffer.put(bb);
                storeTimestamp = bb.getLong(MessageDecoder.MESSAGE_STORE_TIMESTAMP_POSTION);
            }
        } finally {
            getMessageResult.release();
        }

        this.brokerController.getBrokerStatsManager().recordDiskFallBehindTime(group, topic, queueId, this.brokerController.getMessageStore().now() - storeTimestamp);
        return byteBuffer.array();
    }


}
