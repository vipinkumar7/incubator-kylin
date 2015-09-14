/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.job.cube;

import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.HBaseMetadataTestCase;
import org.apache.kylin.common.util.LocalFileMetadataTestCase;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.job.DeployUtil;
import org.apache.kylin.job.common.MapReduceExecutable;
import org.apache.kylin.job.constant.ExecutableConstants;
import org.apache.kylin.job.engine.JobEngineConfig;
import org.apache.kylin.job.hadoop.AbstractHadoopJob;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.junit.*;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Created by sunyerui on 15/8/31.
 */
public class CubingJobBuilderTest extends LocalFileMetadataTestCase {

  private JobEngineConfig jobEngineConfig;

  private CubeManager cubeManager;

  private static final Log logger = LogFactory.getLog(CubingJobBuilderTest.class);

  @Before
  public void before() throws Exception {
    createTestMetadata();
    final KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
    cubeManager = CubeManager.getInstance(kylinConfig);
    jobEngineConfig = new JobEngineConfig(kylinConfig);
  }

  @After
  public void after() throws Exception {
    cleanupTestMetadata();
  }

  public static void afterClass() {
    staticCleanupTestMetadata();
  }

  private CubeSegment buildSegment() throws ParseException, IOException {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
    f.setTimeZone(TimeZone.getTimeZone("GMT"));
    // this cube's start date is 0, end date is 20501112000000
    long date2 = f.parse("2022-01-01").getTime();

    CubeSegment segment = cubeManager.appendSegments(cubeManager.getCube("test_kylin_cube_without_slr_empty"), date2);
    // just to cheat the cubeManager.getBuildingSegments checking
    segment.setStatus(SegmentStatusEnum.READY);

    return segment;
  }

  private static class ParseOptionHelperJob extends AbstractHadoopJob {
    @Override
    public int run(String[] strings) throws Exception {
      return 0;
    }

    public void parseOptionsForRowkeyDistributionStep(String arg) throws org.apache.commons.cli.ParseException, IOException {
      Options options = new Options();
      options.addOption(OPTION_INPUT_PATH);
      options.addOption(OPTION_OUTPUT_PATH);
      options.addOption(OPTION_JOB_NAME);
      options.addOption(OPTION_CUBE_NAME);

      GenericOptionsParser hadoopParser = new GenericOptionsParser(new Configuration(), arg.trim().split("\\s+"));
      String[] toolArgs = hadoopParser.getRemainingArgs();
      parseOptions(options, toolArgs);
    }

    public String getOutputPath() {
      return getOptionValue(OPTION_OUTPUT_PATH);
    }
  }

  private static final Pattern UUID_PATTERN = Pattern.compile(".*kylin-([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}).*rowkey_stats");
  @Test
  public void testBuildJob() throws ParseException, IOException, org.apache.commons.cli.ParseException {
    CubeSegment segment = buildSegment();
    CubingJobBuilder cubingJobBuilder = new CubingJobBuilder(jobEngineConfig);
    CubingJob job = cubingJobBuilder.buildJob(segment);
    assertNotNull(job);

    // here should be more asserts for every step in building
    // only check rowkey distribution step for now
    MapReduceExecutable rowkeyDistributionStep = (MapReduceExecutable)job.getTaskByName(ExecutableConstants.STEP_NAME_GET_CUBOID_KEY_DISTRIBUTION);
    assertNotNull(rowkeyDistributionStep);
    String mrParams = rowkeyDistributionStep.getMapReduceParams();
    assertNotNull(mrParams);
    logger.info("mrParams: " + mrParams);
    // parse output path and check
    ParseOptionHelperJob parseHelper = new ParseOptionHelperJob();
    parseHelper.parseOptionsForRowkeyDistributionStep(mrParams);
    String outputPath = parseHelper.getOutputPath();
    logger.info("output: " + outputPath);
    Matcher m = UUID_PATTERN.matcher(outputPath);
    assertTrue(m.find());
    assertEquals(2, m.groupCount());
    assertEquals(job.getId(), m.group(1));
  }
}
