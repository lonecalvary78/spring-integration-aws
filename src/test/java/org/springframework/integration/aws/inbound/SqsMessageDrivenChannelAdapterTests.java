/*
 * Copyright 2015-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.aws.inbound;

import java.util.Map;

import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aws.LocalstackContainerTest;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageProducer;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Artem Bilan
 */
@SpringJUnitConfig
@DirtiesContext
class SqsMessageDrivenChannelAdapterTests implements LocalstackContainerTest {

	private static SqsAsyncClient AMAZON_SQS;

	private static String testQueueUrl;

	@Autowired
	private PollableChannel inputChannel;

	@BeforeAll
	static void setup() {
		AMAZON_SQS = LocalstackContainerTest.sqsClient();
		testQueueUrl = AMAZON_SQS.createQueue(request -> request.queueName("testQueue")).join().queueUrl();
	}

	@Test
	void sqsMessageDrivenChannelAdapter() {
		Map<String, MessageAttributeValue> attributes =
				Map.of("someAttribute",
						MessageAttributeValue.builder()
								.stringValue("someValue")
								.dataType("String")
								.build());

		AMAZON_SQS.sendMessageBatch(request ->
				request.queueUrl(testQueueUrl)
						.entries(SendMessageBatchRequestEntry.builder()
										.messageBody("messageContent")
										.id("messageContent_id")
										.messageAttributes(attributes)
										.build(),
								SendMessageBatchRequestEntry.builder()
										.messageBody("messageContent2")
										.id("messageContent2_id")
										.messageAttributes(attributes)
										.build()));

		org.springframework.messaging.Message<?> receive = this.inputChannel.receive(10000);
		assertThat(receive).isNotNull();
		assertThat((String) receive.getPayload()).isIn("messageContent", "messageContent2");
		assertThat(receive.getHeaders().get(SqsHeaders.SQS_QUEUE_NAME_HEADER)).isEqualTo("testQueue");
		assertThat(receive.getHeaders().get("someAttribute")).isEqualTo("someValue");

		receive = this.inputChannel.receive(10000);
		assertThat(receive).isNotNull();
		assertThat((String) receive.getPayload()).isIn("messageContent", "messageContent2");
		assertThat(receive.getHeaders().get(SqsHeaders.SQS_QUEUE_NAME_HEADER)).isEqualTo("testQueue");
		assertThat(receive.getHeaders().get("someAttribute")).isEqualTo("someValue");
	}

	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		public PollableChannel inputChannel() {
			return new QueueChannel();
		}

		@Bean
		public MessageProducer sqsMessageDrivenChannelAdapter() {
			SqsMessageDrivenChannelAdapter adapter = new SqsMessageDrivenChannelAdapter(AMAZON_SQS, "testQueue");
			adapter.setOutputChannel(inputChannel());
			return adapter;
		}

	}

}
