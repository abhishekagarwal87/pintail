package com.inmobi.messaging.consumer.examples;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.io.Text;

import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.consumer.MessageConsumer;
import com.inmobi.messaging.consumer.MessageConsumerFactory;
import com.inmobi.messaging.consumer.audit.AuditStatsQuery;
import com.inmobi.messaging.publisher.AbstractMessagePublisher;
import com.inmobi.messaging.publisher.MessagePublisherFactory;
import com.inmobi.messaging.util.ConsumerUtil;

public class StreamingBenchmark {

  static final String DELIMITER = "/t";
  static final SimpleDateFormat LogDateFormat = new SimpleDateFormat(
      "yyyy:MM:dd hh:mm:ss");

  static final int WRONG_USAGE_CODE = -1;
  static final int FAILED_CODE = 1;
  static final int HEADER_LENGTH = 16;

  static int printUsage() {
    System.out.println(
        "Usage: StreamingBenchmark  " +
        " [-producer <topic-name> <no-of-msgs> <no-of-msgs-per-sec>" +
          " [<timeoutSeconds> <msg-size>]]" +
        " [-consumer <no-of-producers> <no-of-msgs>" +
          " [<timeoutSeconds> <msg-size> <hadoopconsumerflag> <timezone>]]");
    return WRONG_USAGE_CODE;
  }

  public static void main(String[] args) throws Exception {
    int exitcode = run(args);
    System.exit(exitcode);
  }

  static int numProducerArgs = 5;
  static int numProducerRequiredArgs = 3;
  static int numConsumerArgs = 5;
  static int numConsumerRequiredArgs = 2;
  static int minArgs = 3;
  public static int run(String[] args) throws Exception {
    if (args.length < minArgs) {
      return printUsage();
    }
    long maxSent = -1;
    float numMsgsPerSec = -1;
    String timezone = null;
    String topic = null;
    int numProducers = 1;
    boolean runProducer = false;
    boolean runConsumer = false;
    boolean hadoopConsumer = false;
    int producerTimeout = 0;
    int consumerTimeout = 0;
    int msgSize = 2000;
    String auditStartTime = null;
    String auditEndTime = null;
    String auditRootDir = null;
    String auditTopic = null;
    SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy-HH:mm");

    if (args.length >= minArgs) {
      int consumerOptionIndex = -1;
      if (args[0].equals("-producer")) {
        if (args.length < (numProducerRequiredArgs + 1)) {
          return printUsage();
        }
        topic = args[1];
        maxSent = Long.parseLong(args[2]);
        numMsgsPerSec = Float.parseFloat(args[3]);
        runProducer = true;
        if (args.length > 4 && !args[4].equals("-consumer")) {
          producerTimeout = Integer.parseInt(args[4]);
          System.out.println("producerTimeout :" + producerTimeout + " seconds");
          if (args.length > 5 && !args[5].equals("-consumer")) {
            msgSize = Integer.parseInt(args[5]);
            consumerOptionIndex = 6;
          } else {
            consumerOptionIndex = 5;
          }
        } else {
          consumerOptionIndex = 4;
        }
      } else {
        consumerOptionIndex = 0;
      }
      
      if (args.length > consumerOptionIndex) {
        if (args[consumerOptionIndex].equals("-consumer")) {
          numProducers = Integer.parseInt(args[consumerOptionIndex + 1]);
          maxSent = Long.parseLong(args[consumerOptionIndex + 2]);
          if (args.length > consumerOptionIndex + 3) {
            consumerTimeout = Integer.parseInt(args[consumerOptionIndex + 3]);
            System.out.println("consumerTimeout :" + consumerTimeout + " seconds");
          }
          if (args.length > consumerOptionIndex + 4) {
            msgSize = Integer.parseInt(args[consumerOptionIndex + 4]);
          }
          if (args.length > consumerOptionIndex + 5) {
            hadoopConsumer = (Integer.parseInt(args[consumerOptionIndex + 5])
                > 0);
          }
          if (args.length > consumerOptionIndex + 6) {
            timezone = args[consumerOptionIndex + 6];
          }
          runConsumer = true;
        }
      }
    } else {
      return printUsage();
    }

    assert(runProducer || runConsumer == true);
    Producer producer = null;
    Consumer consumer = null;
    StatusLogger statusPrinter;

    if (runProducer) {
      System.out.println("Using topic: " + topic);
      producer = createProducer(topic, maxSent, numMsgsPerSec, msgSize);
      producer.start();
    }
    
    if (runConsumer) {
      ClientConfig config = ClientConfig.loadFromClasspath(
          MessageConsumerFactory.MESSAGE_CLIENT_CONF_FILE);
      Date now;
      if (timezone != null) {
        now = ConsumerUtil.getCurrenDateForTimeZone(timezone);
      } else {
        now = Calendar.getInstance().getTime(); 
      }
      System.out.println("Starting from " + now);
      // set consumer start time as auditStartTime
      auditStartTime = formatter.format(now);
      auditRootDir = config.getString(AuditStatsQuery.ROOT_DIR_KEY) + "/system";
      auditTopic = config.getString(MessageConsumerFactory.TOPIC_NAME_KEY);

      // create and start consumer
      assert(config != null);
      consumer = createConsumer(config, maxSent, now, numProducers,
          hadoopConsumer, msgSize);
      consumer.start();
    }
    
    statusPrinter = new StatusLogger(producer, consumer);
    statusPrinter.start();

    int exitcode = 0;
    if (runProducer) {
      assert (producer != null);
      producer.join(producerTimeout * 1000);
      System.out.println("Producer thread state:" + producer.getState());
      exitcode = producer.exitcode;
      if (exitcode == FAILED_CODE) {
        System.out.println("Producer FAILED!");
      } else {
        System.out.println("Producer SUCCESS!");
      }
      if (!runConsumer) {
        statusPrinter.stopped = true;
      }
    } 
    if (runConsumer) {
      assert (consumer != null);
      consumer.join(consumerTimeout * 1000);
      System.out.println("Consumer thread state: "+ consumer.getState());
      statusPrinter.stopped = true;
      // set consumer end time as auditEndTime
      Date now;
      if (timezone != null) {
        now = ConsumerUtil.getCurrenDateForTimeZone(timezone);
      } else {
        now = Calendar.getInstance().getTime(); 
      }
      auditEndTime = formatter.format(now);
    }

    statusPrinter.join();
    if (runConsumer) {
      // start audit thread to perform audit query
      AuditThread auditThread = createAuditThread(auditTopic, auditRootDir,
    		  auditStartTime, auditEndTime);
      auditThread.start();
      
      // wait for audit thread to join
      assert (auditThread != null);
      auditThread.join(consumerTimeout * 1000);
      System.out.println("Audit thread state: "+ auditThread.getState());
    	
      if (!consumer.success) {
        System.out.println("Data validation FAILED!");
        exitcode = FAILED_CODE;
      } else {
        System.out.println("Data validation SUCCESS!");
      }
    }
    return exitcode;
  }

  static Producer createProducer(String topic, long maxSent, float numMsgsPerSec,
      int msgSize) throws IOException {
    return new Producer(topic, maxSent, numMsgsPerSec, msgSize); 
  }

  static Consumer createConsumer(ClientConfig config, long maxSent,
      Date startTime, int numProducers, boolean hadoopConsumer, int maxSize)
          throws IOException {
    return new Consumer(config, maxSent, startTime, numProducers,
        hadoopConsumer, maxSize);    
  }
  
  static AuditThread createAuditThread(String topic, String rootDir, 
		  String fromTime, String toTime) {
	  return new AuditThread(topic, rootDir, fromTime, toTime);
  }

  static class Producer extends Thread {
    volatile AbstractMessagePublisher publisher;
    final String topic;
    final long maxSent;
    final long sleepMillis;
    final long numMsgsPerSleepInterval;
    int exitcode = FAILED_CODE;
    byte[] fixedMsg;

    Producer(String topic, long maxSent, float numMsgsPerSec, int msgSize)
        throws IOException {
      this.topic = topic;
      this.maxSent = maxSent;
      if (maxSent <= 0) {
        throw new IllegalArgumentException("Invalid total number of messages");
      }
      if (numMsgsPerSec > 1000) {
        this.sleepMillis = 1;
        numMsgsPerSleepInterval= (int)(numMsgsPerSec/1000);        
      } else {
        if (numMsgsPerSec <= 0) {
          throw new IllegalArgumentException("Invalid number of messages per" +
          		" second");
        }
        this.sleepMillis = (int)(1000/numMsgsPerSec);
        numMsgsPerSleepInterval = 1;
      }
      fixedMsg = getMessageBytes(msgSize);
      publisher = (AbstractMessagePublisher) MessagePublisherFactory.create();
    }

    @Override
    public void run() {
      System.out.println("Producer started!");
      long msgIndex = 1;
      boolean sentAll= false;
      while (true) {
        for (long j = 0; j < numMsgsPerSleepInterval; j++) {
          publisher.publish(topic, constructMessage(msgIndex, fixedMsg));
          if (msgIndex == maxSent) {
            sentAll = true;
            break;
          }
          msgIndex++;
        }
        if (sentAll) {
          break;
        }
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
          e.printStackTrace();
          return;
        }
      }
      publisher.close();
      System.out.println("Producer closed");
      if (publisher.getStats(topic).getSuccessCount() == maxSent) {
        exitcode = 0;
      }
    }
  }

  static byte[] getMessageBytes(int msgSize) {
    byte[] msg = new byte[msgSize];
    for (int i = 0; i < msgSize; i++) {
      msg[i] = 'A';
    }
    return msg;
  }

  static Message constructMessage(long msgIndex, byte[] randomBytes) {
    long time = System.currentTimeMillis();
    String s = msgIndex + DELIMITER + Long.toString(time) + DELIMITER;
    byte[] msgBytes = new byte[s.length() + randomBytes.length];
    System.arraycopy(s.getBytes(), 0, msgBytes, 0, s.length());
    System.arraycopy(randomBytes, 0, msgBytes, s.length(), randomBytes.length);
    return new Message(ByteBuffer.wrap(msgBytes));
  }

  static String getMessage(Message msg, boolean hadoopConsumer)
      throws IOException {
    byte[] data = msg.getData().array();
    byte[] byteArray = ConsumerUtil.removeHeader(data).array();
    if (!hadoopConsumer) {
      return new String(byteArray);
    } else {
      Text text = new Text();
      ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
      text.readFields(new DataInputStream(bais));
      byte[] decoded = Base64.decodeBase64(text.getBytes());

      return new String(decoded);
    }
  }

  static class Consumer extends Thread {
    final TreeMap<Long, Integer> messageToProducerCount;
    final MessageConsumer consumer;
    final long maxSent;
    volatile long received = 0;
    volatile long totalLatency = 0;
    int numProducers;
    boolean success = false;
    boolean hadoopConsumer = false;
    int numDuplicates = 0;
    long nextElementToPurge = 1;
    String fixedMsg;
    int mismatches = 0;
    int corrupt = 0;

    Consumer(ClientConfig config, long maxSent, Date startTime,
        int numProducers, boolean hadoopConsumer, int msgSize)
            throws IOException {
      this.maxSent = maxSent;
      messageToProducerCount = new TreeMap<Long, Integer>();
      this.numProducers = numProducers;
      consumer = MessageConsumerFactory.create(config, startTime);
      this.hadoopConsumer = hadoopConsumer;
      this.fixedMsg = new String(getMessageBytes(msgSize));
    }

    private void purgeCounts() {
      Set<Map.Entry<Long, Integer>> entrySet = messageToProducerCount.entrySet();
      Iterator<Map.Entry<Long, Integer>> iter = entrySet.iterator();
      while (iter.hasNext()) {
        Map.Entry<Long, Integer> entry = iter.next();
        long msgIndex = entry.getKey();
        int pcount = entry.getValue();
        if (messageToProducerCount.size() > 1) {
          if (msgIndex == nextElementToPurge) {
            if (pcount >= numProducers) {
              iter.remove();
              nextElementToPurge++;
              if (pcount > numProducers) {
                numDuplicates += (pcount - numProducers);
              }
              continue;
            } 
          }
        } 
        break;
      }
    }

    @Override
    public void run() {
      System.out.println("Consumer started!");
      while (true) {
        if (received == maxSent * numProducers) {
          break;
        }
        Message msg = null;
        try {
          msg = consumer.next();
          received++;
          String s = getMessage(msg, hadoopConsumer);
          String[] ar = s.split(DELIMITER);
          Long seq = Long.parseLong(ar[0]);
          Integer pcount = messageToProducerCount.get(seq);
          if (seq < nextElementToPurge) {
            numDuplicates++;
          } else {
            if (pcount == null) {
              messageToProducerCount.put(seq, new Integer(1));
            } else {
              pcount++;
              messageToProducerCount.put(seq, pcount);
            }
            long sentTime = Long.parseLong(ar[1]);
            totalLatency += System.currentTimeMillis() - sentTime;
            
            if (!fixedMsg.equals(ar[2])) {
              mismatches++;
            }
          }
          purgeCounts();
        } catch (Exception e) {
          corrupt++;
          e.printStackTrace();
        }
      }
      purgeCounts();
      for (int pcount : messageToProducerCount.values()) {
        if (pcount > numProducers) {
          numDuplicates += (pcount - numProducers);
        }
      }
      if (numDuplicates != 0) {
        success = false;
      } else {
        Set<Map.Entry<Long, Integer>> entrySet = 
            messageToProducerCount.entrySet();
        if (entrySet.size() != 1) {
          // could happen in the case where messages are received by the
          // consumer after the purging has been done for that message's index
          // i.e older messages
          System.out
              .println("More than one entries in the message-producer map");
          success = false;
        } else {
          // the last entry in the message-producer map should be that of the
          // last msg sent i.e. msgIndex should be maxSent as purging would not
          // happen unless the size of the map is > 1 and for the last message
          // the size of map would be 1
          for (Map.Entry<Long, Integer> entry : entrySet) {
            long msgIndex = entry.getKey();
            int pcount = entry.getValue();
            if (msgIndex == maxSent) {
              if (pcount != numProducers) {
                System.out
                    .println("No of msgs received for the last msg != numProducers");
                System.out.println("Expected " + numProducers + " Received "
                    + pcount);
                success = false;
                break;
              } else {
                success = true;
              }
            } else {
              System.out
                  .println("The last entry is not that of the last msg sent");
              success = false;
              break;
            }
          }
        }
      }
      if (mismatches != 0) {
        System.out.println("Number of mismatches:" + mismatches);
        success = false;
      }
      if (corrupt != 0) {
        System.out.println("Corrupt messages:" + corrupt);
        success = false;
      }
      consumer.close();
      System.out.println("Consumer closed");
    }

  }

  static class StatusLogger extends Thread {
    volatile boolean stopped;
    Producer producer;
    Consumer consumer;
    StatusLogger(Producer producer, Consumer consumer) {
      this.producer = producer;
      this.consumer = consumer;
    }
    @Override
    public void run() {
      while(!stopped) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          e.printStackTrace();
          return;
        }
        StringBuffer sb = new StringBuffer();
        sb.append(LogDateFormat.format(System.currentTimeMillis()));
        if (producer != null) {
          constructProducerString(sb);
        }
        if (consumer != null) {
          constructConsumerString(sb);
        }
        System.out.println(sb.toString());
      }
    }
    
    void constructProducerString(StringBuffer sb) {
      sb.append(" Invocations:" + producer.publisher.getStats(producer.topic).
          getInvocationCount());
      sb.append(" Inflight:" + producer.publisher.getStats(producer.topic)
          .getInFlight());
      sb.append(" SentSuccess:" + producer.publisher.getStats(producer.topic).
          getSuccessCount());
      sb.append(" GracefulTerminates:" + producer.publisher.getStats(
          producer.topic).getGracefulTerminates());
      sb.append(" UnhandledExceptions:" + producer.publisher.getStats(
          producer.topic).getUnhandledExceptionCount());
    }
    
    void constructConsumerString(StringBuffer sb) {
      sb.append(" Received:" + consumer.received);
      sb.append(" Duplicates:");
      sb.append(consumer.numDuplicates);
      if (consumer.received != 0) {
        sb.append(" MeanLatency(ms):" 
            + (consumer.totalLatency / consumer.received));
      }      
    }
  }
  
  static class AuditThread extends Thread {
	  final String topic;
	  final String rootDir;
	  final String startTime;
	  final String endTime;
	  
	  AuditThread(String topic, String rootDir, String startTime, String endTime) {
		  this.topic = topic;
		  this.rootDir = rootDir;
		  this.startTime = startTime;
		  this.endTime = endTime;
	  }
	  
	  @Override
	  public void run() {
		  System.out.println("Audit Thread started!");
		  
		  // prepare args to AuditStatsQuery
		  String args[] = new String[12];
		  args[0] = "-filter";
		  args[1] = "TOPIC=" + topic;
		  args[2] = "-group";
		  args[3] = "tier";
		  args[4] = "-rootdir";
		  args[5] = rootDir;
		  args[6] = startTime;
		  args[7] = endTime;
		  args[8] = "-cutoff";
		  args[9] = "10";
		  args[10] = "-timeout";
		  args[11] = "2";
		  
		  // print audit arguments
		  StringBuffer auditArgs = new StringBuffer(100);
		  for (int i = 0; i < args.length; i++) {
			  auditArgs.append(args[i]).append(" ");
		  }
		  System.out.println("Executing Audit Query with arguments: " + auditArgs);
		  
		  AuditStatsQuery.main(args);
		  System.out.println("Audit Thread closed");
	  }
  }
  
}
