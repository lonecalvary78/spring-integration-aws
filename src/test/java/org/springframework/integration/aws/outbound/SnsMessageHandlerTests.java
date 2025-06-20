/*
 * Copyright 2016-present the original author or authors.
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

package org.springframework.integration.aws.outbound;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.aws.support.SnsBodyBuilder;
import org.springframework.integration.aws.support.SnsHeaderMapper;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author Artem Bilan
 * @author Christopher Smith
 */
@SpringJUnitConfig
@DirtiesContext
class SnsMessageHandlerTests {

	private static final SpelExpressionParser PARSER = new SpelExpressionParser();

	@Autowired
	private MessageChannel sendToSnsChannel;

	@Autowired
	private SnsAsyncClient amazonSNS;

	@Autowired
	private PollableChannel resultChannel;

	@Test
	void snsMessageHandler() {
		SnsBodyBuilder payload = SnsBodyBuilder.withDefault("foo").forProtocols("{\"foo\" : \"bar\"}", "sms");

		Message<?> message = MessageBuilder.withPayload(payload).setHeader("topic", "topic")
				.setHeader("subject", "subject").setHeader("foo", "bar").build();

		this.sendToSnsChannel.send(message);

		Message<?> reply = this.resultChannel.receive(10000);
		assertThat(reply).isNotNull();

		ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
		verify(this.amazonSNS).publish(captor.capture());

		PublishRequest publishRequest = captor.getValue();

		assertThat(publishRequest.messageStructure()).isEqualTo("json");
		assertThat(publishRequest.topicArn()).isEqualTo("arn:aws:sns:eu-west-1:111111111111:topic.fifo");
		assertThat(publishRequest.subject()).isEqualTo("subject");
		assertThat(publishRequest.messageGroupId()).isEqualTo("SUBJECT");
		assertThat(publishRequest.messageDeduplicationId()).isEqualTo("BAR");
		assertThat(publishRequest.message())
				.isEqualTo("{\"default\":\"foo\",\"sms\":\"{\\\"foo\\\" : \\\"bar\\\"}\"}");

		Map<String, MessageAttributeValue> messageAttributes = publishRequest.messageAttributes();

		assertThat(messageAttributes)
				.doesNotContainKeys(MessageHeaders.ID, MessageHeaders.TIMESTAMP)
				.containsKey("foo");
		assertThat(messageAttributes.get("foo").stringValue()).isEqualTo("bar");

		assertThat(reply.getHeaders().get(AwsHeaders.MESSAGE_ID)).isEqualTo("111");
		assertThat(reply.getHeaders().get(AwsHeaders.TOPIC)).isEqualTo("arn:aws:sns:eu-west-1:111111111111:topic.fifo");
		assertThat(reply.getPayload()).isSameAs(payload);
	}

	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		@SuppressWarnings("unchecked")
		public SnsAsyncClient amazonSNS() {
			SnsAsyncClient mock = mock(SnsAsyncClient.class);

			willAnswer(invocation ->
					CompletableFuture.completedFuture(
							CreateTopicResponse.builder()
									.topicArn("arn:aws:sns:eu-west-1:111111111111:topic.fifo")
									.build()))
					.given(mock)
					.createTopic(any(Consumer.class));

			willAnswer(invocation ->
					CompletableFuture.completedFuture(PublishResponse.builder().messageId("111").build()))
					.given(mock)
					.publish(any(PublishRequest.class));

			return mock;
		}

		@Bean
		public PollableChannel resultChannel() {
			return new QueueChannel();
		}

		@Bean
		@ServiceActivator(inputChannel = "sendToSnsChannel")
		public MessageHandler snsMessageHandler() {
			SnsMessageHandler snsMessageHandler = new SnsMessageHandler(amazonSNS());
			snsMessageHandler.setTopicArnExpression(PARSER.parseExpression("headers.topic"));
			snsMessageHandler.setMessageGroupIdExpression(PARSER.parseExpression("headers.subject.toUpperCase()"));
			snsMessageHandler.setMessageDeduplicationIdExpression(PARSER.parseExpression("headers.foo.toUpperCase()"));
			snsMessageHandler.setSubjectExpression(PARSER.parseExpression("headers.subject"));
			snsMessageHandler.setBodyExpression(PARSER.parseExpression("payload"));
			snsMessageHandler.setAsync(true);
			snsMessageHandler.setOutputChannel(resultChannel());
			SnsHeaderMapper headerMapper = new SnsHeaderMapper();
			headerMapper.setOutboundHeaderNames("foo");
			snsMessageHandler.setHeaderMapper(headerMapper);
			return snsMessageHandler;
		}

	}

}
