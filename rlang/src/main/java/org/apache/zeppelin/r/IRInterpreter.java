/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.r;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.zeppelin.interpreter.BaseZeppelinContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.jupyter.proto.ExecuteRequest;
import org.apache.zeppelin.interpreter.jupyter.proto.ExecuteResponse;
import org.apache.zeppelin.interpreter.jupyter.proto.ExecuteStatus;
import org.apache.zeppelin.jupyter.JupyterKernelInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * R Interpreter which use the IRKernel (https://github.com/IRkernel/IRkernel),
 * Besides that it use Spark to setup communication channel between JVM and R process, so that user
 * can use ZeppelinContext.
 */
public class IRInterpreter extends JupyterKernelInterpreter {

  private static final Logger LOGGER = LoggerFactory.getLogger(IRInterpreter.class);

  private SparkRBackend sparkRBackend;

  public IRInterpreter(Properties properties) {
    super(properties);
  }

  /**
   * RInterpreter just use spark-core for the communication between R process and jvm process.
   * SparkContext is not created in this RInterpreter.
   * Sub class can override this, e.g. SparkRInterpreter
   * @return
   */
  protected boolean isSparkSupported() {
    return false;
  }

  /**
   * The spark version specified in pom.xml
   * Sub class can override this, e.g. SparkRInterpreter
   * @return
   */
  protected int sparkVersion() {
    return 20403;
  }

  /**
   * Spark 2.4.3 need secret for socket communication between R process and jvm process.
   * Sub class can override this, e.g. SparkRInterpreter
   * @return
   */
  protected boolean isSecretSupported() {
    return true;
  }

  @Override
  public void open() throws InterpreterException {
    super.open();

    this.sparkRBackend = SparkRBackend.get();
    // Share the same SparkRBackend across sessions
    synchronized (sparkRBackend) {
      if (!sparkRBackend.isStarted()) {
        try {
          sparkRBackend.init(isSecretSupported());
        } catch (Exception e) {
          throw new InterpreterException("Fail to init SparkRBackend", e);
        }
        sparkRBackend.start();
      }
    }

    try {
      initIRKernel();
    } catch (IOException e) {
      throw new InterpreterException("Fail to init IR Kernel:\n" +
              ExceptionUtils.getStackTrace(e), e);
    }
  }

  /**
   * Init IRKernel by execute R script zeppelin-isparkr.R
   * @throws IOException
   * @throws InterpreterException
   */
  protected void initIRKernel() throws IOException, InterpreterException {
    String timeout = getProperty("spark.r.backendConnectionTimeout", "6000");
    InputStream input =
            getClass().getClassLoader().getResourceAsStream("R/zeppelin_isparkr.R");
    String code = IOUtils.toString(input)
            .replace("${Port}", sparkRBackend.port() + "")
            .replace("${version}", sparkVersion() + "")
            .replace("${libPath}", "\"" + SparkRUtils.getSparkRLib(isSparkSupported()) + "\"")
            .replace("${timeout}", timeout)
            .replace("${isSparkSupported}", "\"" + isSparkSupported() + "\"")
            .replace("${authSecret}", "\"" + sparkRBackend.socketSecret() + "\"");
    LOGGER.info("Init IRKernel via script:\n" + code);
    ExecuteResponse response = jupyterKernelClient.block_execute(ExecuteRequest.newBuilder()
            .setCode(code).build());
    if (response.getStatus() != ExecuteStatus.SUCCESS) {
      throw new IOException("Fail to setup JVMGateway\n" + response.getOutput());
    }
  }

  @Override
  public String getKernelName() {
    return "ir";
  }

  @Override
  public BaseZeppelinContext buildZeppelinContext() {
    return new RZeppelinContext(getInterpreterGroup().getInterpreterHookRegistry(),
            Integer.parseInt(getProperty("zeppelin.r.maxResult", "1000")));
  }
}
