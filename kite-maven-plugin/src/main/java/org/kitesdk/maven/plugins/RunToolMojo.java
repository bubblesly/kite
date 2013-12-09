/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.maven.plugins;

import com.google.common.base.Joiner;
import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Run a Hadoop tool on the local machine.
 */
@Mojo(name = "run-tool", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class RunToolMojo extends AbstractHadoopMojo {

  /**
   * The tool class to run. The specified class must have a standard Java
   * <code>main</code> method.
   */
  @Parameter(property = "kite.toolClass", required = true)
  private String toolClass;

  /**
   * Arguments to pass to the tool, in addition to those generated by
   * <code>addDependenciesToDistributedCache</code> and <code>hadoopConfiguration</code>.
   */
  @Parameter(property = "kite.args")
  private String[] args;

  /**
   * Whether to add dependencies in the <i>runtime</i> classpath to Hadoop's distributed
   * cache so that they are added to the classpath for MapReduce tasks
   * (via <code>-libjars</code>).
   */
  @Parameter(property = "kite.addDependenciesToDistributedCache",
      defaultValue = "true")
  private boolean addDependenciesToDistributedCache;

  /**
   * Hadoop configuration properties.
   *
   * WARNING: This configuration setting is not compatible with the factory
   * methods in {@link org.kitesdk.data.DatasetRepositories} because it
   * does not alter the environment configuration. For example, if using
   * this to modify the environment's "fs.defaultFS" property in the tool that
   * is run by this Mojo, opening a repo by URI will continue to use the
   * environment's default FS.
   *
   * Configuration properties set using this option will only affect the
   * {@link org.apache.hadoop.conf.Configuration} objects passed by
   * {@link org.apache.hadoop.util.ToolRunner} or created by
   * {@link org.apache.hadoop.util.GenericOptionsParser}.
   */
  @Parameter(property = "kite.hadoopConfiguration")
  private Properties hadoopConfiguration;

  public void execute() throws MojoExecutionException, MojoFailureException {
    List<String> libJars = new ArrayList<String>();
    List<URL> classpath = new ArrayList<URL>();

    File mainArtifactFile = new File(mavenProject.getBuild().getDirectory(),
        mavenProject.getBuild().getFinalName() + ".jar");
    if (!mainArtifactFile.exists()) {
      throw new MojoExecutionException("Main artifact missing: " + mainArtifactFile);
    }
    libJars.add(mainArtifactFile.toString());
    classpath.add(toURL(mainArtifactFile));
    for (Object a : mavenProject.getRuntimeArtifacts()) {
      File file = ((Artifact) a).getFile();
      classpath.add(toURL(file));
      libJars.add(file.toString());
    }

    final List<String> commandArgs = new ArrayList<String>();
    for (String key : hadoopConfiguration.stringPropertyNames()) {
      String value = hadoopConfiguration.getProperty(key);
      commandArgs.add("-D");
      commandArgs.add(key + "=" + value);
    }
    if (addDependenciesToDistributedCache) {
      commandArgs.add("-libjars");
      commandArgs.add(Joiner.on(',').join(libJars));
    }
    if (args != null) {
      for (String arg : args) {
        commandArgs.add(arg);
      }
    }

    getLog().debug("Running tool with args: " + commandArgs);
    getLog().debug("Running tool with classpath: " + classpath);

    Thread executionThread = new Thread() {
      @Override
      public void run() {
        try {
          Method main = Thread.currentThread().getContextClassLoader().loadClass(toolClass)
              .getMethod("main", new Class[]{ String[].class });
          main.invoke(null, new Object[] { commandArgs.toArray(new String[commandArgs.size()]) });
        } catch (Exception e) {
          Thread.currentThread().getThreadGroup().uncaughtException(
              Thread.currentThread(), e);
        }
      }
    };
    ClassLoader parentClassLoader = getClass().getClassLoader(); // use Maven's classloader, not the system one
    ClassLoader classLoader = new URLClassLoader(
        classpath.toArray(new URL[classpath.size()]), parentClassLoader);
    executionThread.setContextClassLoader(classLoader);
    executionThread.start();
    try {
      executionThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      getLog().warn("interrupted while joining against thread " + executionThread, e);
    }
  }

  private URL toURL(File file) throws MojoExecutionException {
    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new MojoExecutionException("Can't convert file  to URL: " + file, e);
    }
  }
}
