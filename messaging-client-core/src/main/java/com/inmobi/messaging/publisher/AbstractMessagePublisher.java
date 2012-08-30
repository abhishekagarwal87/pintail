package com.inmobi.messaging.publisher;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.inmobi.instrumentation.MessagingClientStatBuilder;
import com.inmobi.instrumentation.TimingAccumulator;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;
import com.inmobi.stats.StatsExposer;

/**
 * Abstract class implementing {@link MessagePublisher} interface.
 * 
 * Initializes {@link StatsEmitter} and {@link StatsExposer} with configuration
 * defined in file {@value MessagePublisherFactory#EMITTER_CONF_FILE_KEY}. If 
 * no such file exists, statistics will be disabled.
 */
public abstract class AbstractMessagePublisher implements MessagePublisher {

  private static final Logger LOG = LoggerFactory
      .getLogger(AbstractMessagePublisher.class);
  private Map<String, TopicStatsExposer> statsExposers = new HashMap<String,
      TopicStatsExposer>();
  private MessagingClientStatBuilder statsEmitter = new 
      MessagingClientStatBuilder();
  public static final String HEADER_TOPIC = "topic";

  @Override
  public void publish(String topicName, Message m) {
    if (getStats(topicName) == null) {
      try {
        initTopic(topicName, new TimingAccumulator());
      } catch (IOException e) {
        LOG.error("Could not initialize topic. Dropping the message" + m, e);
        throw new IllegalArgumentException("Could not initialize topic", e);
      }
    }

    getStats(topicName).accumulateInvocation();
    // TODO: generate headers
    Map<String, String> headers = new HashMap<String, String>();
    headers.put(HEADER_TOPIC, topicName);
    publish(headers, m);
  }

  protected void initTopic(String topic, TimingAccumulator stats)
      throws IOException {
    TopicStatsExposer statsExposer = new TopicStatsExposer(topic,
        stats);
    statsEmitter.add(statsExposer);
    statsExposers.put(topic, statsExposer);
  }

  protected abstract void publish(Map<String, String> headers, Message m);

  MessagingClientStatBuilder getMetrics() {
    return statsEmitter;
  }

  public TimingAccumulator getStats(String topic) {
    if (statsExposers.get(topic) != null) {
      return statsExposers.get(topic).getTimingAccumulator();
    } else {
      return null;
    }
  }

  TopicStatsExposer getStatsExposer(String topic) {
    return statsExposers.get(topic);
  }

  protected void init(ClientConfig config) throws IOException {
    try {
      String emitterConfig = config
          .getString(MessagePublisherFactory.EMITTER_CONF_FILE_KEY);
      if (emitterConfig == null) {
        LOG.warn("Stat emitter is disabled as config "
            + MessagePublisherFactory.EMITTER_CONF_FILE_KEY + " is not set in" +
            		" the config.");
        return;
      }
      statsEmitter.init(emitterConfig);
    } catch (Exception e) {
      throw new IOException("Couldn't find or initialize the configured stats" +
      		" emitter", e);
    }
  }

  @Override
  public void close() {
    for (StatsExposer statsExposer : statsExposers.values()) {
      statsEmitter.remove(statsExposer);
    }
  }
}
