package com.inmobi.messaging.consumer.hadoop;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.testng.Assert;

import com.inmobi.databus.readers.DatabusStreamWaitingReader;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.consumer.util.ConsumerUtil;
import com.inmobi.messaging.consumer.util.HadoopUtil;

public abstract class TestAbstractHadoopConsumer {

  protected String ck1;
  protected String ck2;
  protected String ck3;
  protected String ck4;
  protected String ck5;

  int numMessagesPerFile = 100;
  int numDataFiles;
  int numSuffixDirs;
  HadoopConsumer testConsumer;
  static final String testStream = "testclient";
  protected String[] dataFiles = new String[]{HadoopUtil.files[0],
    HadoopUtil.files[1],
    HadoopUtil.files[2]};
  protected String[] suffixDirs;
  protected String consumerName;
  protected Path[] rootDirs;
  protected String[] chkDirs = new String[]{ck1, ck2, ck3, ck4, ck5};
  Path[][] finalPaths;
  Configuration conf;
  ClientConfig config;

  abstract ClientConfig loadConfig();

  public void setup() throws Exception {
    // setup 
    config = loadConfig();
    testConsumer = new HadoopConsumer();
    testConsumer.initializeConfig(config);

    conf = testConsumer.getHadoopConf();
    Assert.assertEquals(conf.get("myhadoop.property"), "myvalue");

    rootDirs = testConsumer.getRootDirs();
    numSuffixDirs = suffixDirs != null ? suffixDirs.length : 1;
    numDataFiles = dataFiles != null ? dataFiles.length : 1;
    finalPaths = new Path[rootDirs.length][numSuffixDirs * numDataFiles];
    for (int i = 0; i < rootDirs.length; i++) {
      HadoopUtil.setupHadoopCluster(
        conf, dataFiles, suffixDirs, finalPaths[i], rootDirs[i]);
    }
    HadoopUtil.setUpHadoopFiles(rootDirs[0], conf,
      new String[]{"_SUCCESS", "_DONE"}, suffixDirs, null);
  }

  public void testMarkAndReset() throws Exception {
    config.set(HadoopConsumerConfig.checkpointDirConfig, ck1);
    config.set(HadoopConsumerConfig.rootDirsConfig,
      rootDirs[0].toString());
    ConsumerUtil.testMarkAndReset(config, testStream, consumerName, true);
  }
  
  public void testTimeoutStats() throws Exception {
    config.set(HadoopConsumerConfig.checkpointDirConfig, ck1);
    config.set(HadoopConsumerConfig.rootDirsConfig,
      rootDirs[0].toString());
    ConsumerUtil.testTimeoutStats(config, testStream, consumerName, 
        DatabusStreamWaitingReader.getDateFromStreamDir(
            rootDirs[0], finalPaths[0][0]), true);
  }

  public void testMarkAndResetWithStartTime() throws Exception {
    config.set(HadoopConsumerConfig.checkpointDirConfig, ck2);
    config.set(HadoopConsumerConfig.rootDirsConfig,
      rootDirs[0].toString());
    ConsumerUtil.testMarkAndResetWithStartTime(config, testStream, consumerName,
      DatabusStreamWaitingReader.getDateFromStreamDir(
        rootDirs[0], finalPaths[0][1]), true);
  }

  public void testSuffixDirs() throws Exception {
    config.set(HadoopConsumerConfig.rootDirsConfig,
      rootDirs[0].toString());
    config.set(HadoopConsumerConfig.checkpointDirConfig,
      ck3);
    ConsumerUtil.assertMessages(config, testStream, consumerName, 1,
      numSuffixDirs,
      numDataFiles, numMessagesPerFile, true);
  }


  public void testMultipleClusters() throws Exception {
    config.set(HadoopConsumerConfig.rootDirsConfig,
      rootDirs[0].toString() + "," + rootDirs[1].toString());
    config.set(HadoopConsumerConfig.checkpointDirConfig,
      ck4);

    ConsumerUtil.assertMessages(config, testStream, consumerName, 2,
      numSuffixDirs,
      numDataFiles, numMessagesPerFile, true);
  }

  public void testMultipleClusters2() throws Exception {
    config.set(HadoopConsumerConfig.rootDirsConfig, rootDirs[0].toString()
      + "," + rootDirs[1] + "," + rootDirs[2]);
    config.set(HadoopConsumerConfig.checkpointDirConfig, ck5);
    ConsumerUtil.assertMessages(config, testStream, consumerName, 3,
      numSuffixDirs,
      numDataFiles, numMessagesPerFile, true);
  }

  public void cleanup() throws IOException {
    FileSystem lfs = FileSystem.getLocal(conf);
    for (Path rootDir : rootDirs) {
      lfs.delete(rootDir.getParent(), true);
    }
    //Cleanup checkpoint directories, if we don't clean it up will cause tests to be flaky.
    for (String chk : chkDirs) {
      if (chk != null) {
        Path p = new Path(chk);
        if (lfs.exists(p)) {
          lfs.delete(p, true);
        }
      }
    }
  }
}
