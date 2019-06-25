/*
 * Copyright 2017-2018 the original author or authors.
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

package org.springframework.cloud.stream.binder.kafka.streams;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.stream.binder.BinderConfiguration;
import org.springframework.cloud.stream.binder.kafka.streams.function.FunctionDetectorCondition;
import org.springframework.cloud.stream.binder.kafka.streams.function.KafkaStreamsBindableProxyFactory;
import org.springframework.cloud.stream.binder.kafka.streams.properties.KafkaStreamsBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.streams.properties.KafkaStreamsExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.streams.serde.CompositeNonNativeSerde;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.binding.StreamListenerResultAdapter;
import org.springframework.cloud.stream.config.BinderProperties;
import org.springframework.cloud.stream.config.BindingServiceConfiguration;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.converter.CompositeMessageConverterFactory;
import org.springframework.cloud.stream.function.StreamFunctionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.core.CleanupConfig;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Kafka Streams binder configuration.
 *
 * @author Marius Bogoevici
 * @author Soby Chacko
 * @author Gary Russell
 */
@EnableConfigurationProperties(KafkaStreamsExtendedBindingProperties.class)
@ConditionalOnBean(BindingService.class)
@AutoConfigureAfter(BindingServiceConfiguration.class)
public class KafkaStreamsBinderSupportAutoConfiguration {

	private static final String KSTREAM_BINDER_TYPE = "kstream";

	private static final String KTABLE_BINDER_TYPE = "ktable";

	private static final String GLOBALKTABLE_BINDER_TYPE = "globalktable";

	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.stream.kafka.streams.binder")
	public KafkaStreamsBinderConfigurationProperties binderConfigurationProperties(
			KafkaProperties kafkaProperties, ConfigurableEnvironment environment,
			BindingServiceProperties properties) {
		final Map<String, BinderConfiguration> binderConfigurations = getBinderConfigurations(
				properties);
		for (Map.Entry<String, BinderConfiguration> entry : binderConfigurations
				.entrySet()) {
			final BinderConfiguration binderConfiguration = entry.getValue();
			final String binderType = binderConfiguration.getBinderType();
			if (binderType != null && (binderType.equals(KSTREAM_BINDER_TYPE)
					|| binderType.equals(KTABLE_BINDER_TYPE)
					|| binderType.equals(GLOBALKTABLE_BINDER_TYPE))) {
				Map<String, Object> binderProperties = new HashMap<>();
				this.flatten(null, binderConfiguration.getProperties(), binderProperties);
				environment.getPropertySources().addFirst(
						new MapPropertySource("kafkaStreamsBinderEnv", binderProperties));
			}
		}
		return new KafkaStreamsBinderConfigurationProperties(kafkaProperties);
	}

	// TODO: Lifted from core - good candidate for exposing as a utility method in core.
	private static Map<String, BinderConfiguration> getBinderConfigurations(
			BindingServiceProperties properties) {

		Map<String, BinderConfiguration> binderConfigurations = new HashMap<>();
		Map<String, BinderProperties> declaredBinders = properties.getBinders();

		for (Map.Entry<String, BinderProperties> binderEntry : declaredBinders
				.entrySet()) {
			BinderProperties binderProperties = binderEntry.getValue();
			binderConfigurations.put(binderEntry.getKey(),
					new BinderConfiguration(binderProperties.getType(),
							binderProperties.getEnvironment(),
							binderProperties.isInheritEnvironment(),
							binderProperties.isDefaultCandidate()));
		}
		return binderConfigurations;
	}

	// TODO: Lifted from core - good candidate for exposing as a utility method in core.
	@SuppressWarnings("unchecked")
	private void flatten(String propertyName, Object value,
			Map<String, Object> flattenedProperties) {
		if (value instanceof Map) {
			((Map<Object, Object>) value).forEach((k, v) -> flatten(
					(propertyName != null ? propertyName + "." : "") + k, v,
					flattenedProperties));
		}
		else {
			flattenedProperties.put(propertyName, value.toString());
		}
	}

	@Bean
	public KafkaStreamsConfiguration kafkaStreamsConfiguration(
			KafkaStreamsBinderConfigurationProperties properties,
			Environment environment) {
		KafkaProperties kafkaProperties = properties.getKafkaProperties();
		Map<String, Object> streamsProperties = kafkaProperties.buildStreamsProperties();
		if (kafkaProperties.getStreams().getApplicationId() == null) {
			String applicationName = environment.getProperty("spring.application.name");
			if (applicationName != null) {
				streamsProperties.put(StreamsConfig.APPLICATION_ID_CONFIG,
						applicationName);
			}
		}
		return new KafkaStreamsConfiguration(streamsProperties);
	}

	@Bean("streamConfigGlobalProperties")
	public Map<String, Object> streamConfigGlobalProperties(
			KafkaStreamsBinderConfigurationProperties configProperties,
			KafkaStreamsConfiguration kafkaStreamsConfiguration) {

		Properties properties = kafkaStreamsConfiguration.asProperties();
		// Override Spring Boot bootstrap server setting if left to default with the value
		// configured in the binder
		if (ObjectUtils.isEmpty(properties.get(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG))) {
			properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
					configProperties.getKafkaConnectionString());
		}
		else {
			Object bootstrapServerConfig = properties
					.get(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG);
			if (bootstrapServerConfig instanceof String) {
				@SuppressWarnings("unchecked")
				String bootStrapServers = (String) properties
						.get(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG);
				if (bootStrapServers.equals("localhost:9092")) {
					properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
							configProperties.getKafkaConnectionString());
				}
			}
			else if (bootstrapServerConfig instanceof List) {
				List bootStrapCollection = (List) bootstrapServerConfig;
				if (bootStrapCollection.size() == 1 && bootStrapCollection.get(0).equals("localhost:9092")) {
					properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
							configProperties.getKafkaConnectionString());
				}
			}
		}

		String binderProvidedApplicationId = configProperties.getApplicationId();
		if (StringUtils.hasText(binderProvidedApplicationId)) {
			properties.put(StreamsConfig.APPLICATION_ID_CONFIG,
					binderProvidedApplicationId);
		}

		properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
				Serdes.ByteArraySerde.class.getName());
		properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
				Serdes.ByteArraySerde.class.getName());

		if (configProperties
				.getSerdeError() == KafkaStreamsBinderConfigurationProperties.SerdeError.logAndContinue) {
			properties.put(
					StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
					LogAndContinueExceptionHandler.class.getName());
		}
		else if (configProperties
				.getSerdeError() == KafkaStreamsBinderConfigurationProperties.SerdeError.logAndFail) {
			properties.put(
					StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
					LogAndFailExceptionHandler.class.getName());
		}
		else if (configProperties
				.getSerdeError() == KafkaStreamsBinderConfigurationProperties.SerdeError.sendToDlq) {
			properties.put(
					StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
					SendToDlqAndContinue.class.getName());
		}

		if (!ObjectUtils.isEmpty(configProperties.getConfiguration())) {
			properties.putAll(configProperties.getConfiguration());
		}
		return properties.entrySet().stream().collect(
				Collectors.toMap((e) -> String.valueOf(e.getKey()), Map.Entry::getValue));
	}

	@Bean
	public KStreamStreamListenerResultAdapter kstreamStreamListenerResultAdapter() {
		return new KStreamStreamListenerResultAdapter();
	}

	@Bean
	public KStreamStreamListenerParameterAdapter kstreamStreamListenerParameterAdapter(
			KafkaStreamsMessageConversionDelegate kstreamBoundMessageConversionDelegate,
			KafkaStreamsBindingInformationCatalogue KafkaStreamsBindingInformationCatalogue) {
		return new KStreamStreamListenerParameterAdapter(
				kstreamBoundMessageConversionDelegate,
				KafkaStreamsBindingInformationCatalogue);
	}

	@Bean
	public KafkaStreamsStreamListenerSetupMethodOrchestrator kafkaStreamsStreamListenerSetupMethodOrchestrator(
			BindingServiceProperties bindingServiceProperties,
			KafkaStreamsExtendedBindingProperties kafkaStreamsExtendedBindingProperties,
			KeyValueSerdeResolver keyValueSerdeResolver,
			KafkaStreamsBindingInformationCatalogue kafkaStreamsBindingInformationCatalogue,
			KStreamStreamListenerParameterAdapter kafkaStreamListenerParameterAdapter,
			Collection<StreamListenerResultAdapter> streamListenerResultAdapters,
			ObjectProvider<CleanupConfig> cleanupConfig) {
		return new KafkaStreamsStreamListenerSetupMethodOrchestrator(
				bindingServiceProperties, kafkaStreamsExtendedBindingProperties,
				keyValueSerdeResolver, kafkaStreamsBindingInformationCatalogue,
				kafkaStreamListenerParameterAdapter, streamListenerResultAdapters,
				cleanupConfig.getIfUnique());
	}

	@Bean
	@Conditional(FunctionDetectorCondition.class)
	public KafkaStreamsFunctionProcessor kafkaStreamsFunctionProcessor(BindingServiceProperties bindingServiceProperties,
																	KafkaStreamsExtendedBindingProperties kafkaStreamsExtendedBindingProperties,
																	KeyValueSerdeResolver keyValueSerdeResolver,
																	KafkaStreamsBindingInformationCatalogue kafkaStreamsBindingInformationCatalogue,
																	KafkaStreamsMessageConversionDelegate kafkaStreamsMessageConversionDelegate,
																	ObjectProvider<CleanupConfig> cleanupConfig,
																	FunctionCatalog functionCatalog, KafkaStreamsBindableProxyFactory bindableProxyFactory,
																	StreamFunctionProperties streamFunctionProperties) {
		return new KafkaStreamsFunctionProcessor(bindingServiceProperties, kafkaStreamsExtendedBindingProperties,
				keyValueSerdeResolver, kafkaStreamsBindingInformationCatalogue, kafkaStreamsMessageConversionDelegate,
				cleanupConfig.getIfUnique(), functionCatalog, bindableProxyFactory, streamFunctionProperties);
	}

	@Bean
	public KafkaStreamsMessageConversionDelegate messageConversionDelegate(
																		CompositeMessageConverterFactory compositeMessageConverterFactory,
																		SendToDlqAndContinue sendToDlqAndContinue,
																		KafkaStreamsBindingInformationCatalogue KafkaStreamsBindingInformationCatalogue,
																		KafkaStreamsBinderConfigurationProperties binderConfigurationProperties) {
		return new KafkaStreamsMessageConversionDelegate(compositeMessageConverterFactory, sendToDlqAndContinue,
				KafkaStreamsBindingInformationCatalogue, binderConfigurationProperties);
	}

	@Bean
	public CompositeNonNativeSerde compositeNonNativeSerde(
			CompositeMessageConverterFactory compositeMessageConverterFactory) {
		return new CompositeNonNativeSerde(compositeMessageConverterFactory);
	}

	@Bean
	public KStreamBoundElementFactory kStreamBoundElementFactory(
			BindingServiceProperties bindingServiceProperties,
			KafkaStreamsBindingInformationCatalogue KafkaStreamsBindingInformationCatalogue) {
		return new KStreamBoundElementFactory(bindingServiceProperties,
				KafkaStreamsBindingInformationCatalogue);
	}

	@Bean
	public KTableBoundElementFactory kTableBoundElementFactory(
			BindingServiceProperties bindingServiceProperties) {
		return new KTableBoundElementFactory(bindingServiceProperties);
	}

	@Bean
	public GlobalKTableBoundElementFactory globalKTableBoundElementFactory(
			BindingServiceProperties properties) {
		return new GlobalKTableBoundElementFactory(properties);
	}

	@Bean
	public SendToDlqAndContinue sendToDlqAndContinue() {
		return new SendToDlqAndContinue();
	}

	@Bean
	public KafkaStreamsBindingInformationCatalogue kafkaStreamsBindingInformationCatalogue() {
		return new KafkaStreamsBindingInformationCatalogue();
	}

	@Bean
	@SuppressWarnings("unchecked")
	@ConditionalOnMissingBean
	public KeyValueSerdeResolver keyValueSerdeResolver(
			@Qualifier("streamConfigGlobalProperties") Object streamConfigGlobalProperties,
			KafkaStreamsBinderConfigurationProperties properties) {
		return new KeyValueSerdeResolver(
				(Map<String, Object>) streamConfigGlobalProperties, properties);
	}

	@Bean
	public InteractiveQueryService interactiveQueryServices(
			KafkaStreamsRegistry kafkaStreamsRegistry,
			KafkaStreamsBinderConfigurationProperties properties) {
		return new InteractiveQueryService(kafkaStreamsRegistry, properties);
	}

	@Bean
	public KafkaStreamsRegistry kafkaStreamsRegistry() {
		return new KafkaStreamsRegistry();
	}

	@Bean
	public StreamsBuilderFactoryManager streamsBuilderFactoryManager(
			KafkaStreamsBindingInformationCatalogue catalogue,
			KafkaStreamsRegistry kafkaStreamsRegistry) {
		return new StreamsBuilderFactoryManager(catalogue, kafkaStreamsRegistry);
	}

	@Bean("kafkaStreamsDlqDispatchers")
	public Map<String, KafkaStreamsDlqDispatch> dlqDispatchers() {
		return new HashMap<>();
	}

}
