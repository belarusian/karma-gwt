package jetbrains.datapad.js.tests;

/*
 * Copyright 2012-2016 JetBrains s.r.o
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


@Mojo(name = "run-tests", defaultPhase = LifecyclePhase.INTEGRATION_TEST)
public class RunTestsMojo extends AbstractMojo {

  private enum Resource {
    LIB("lib"), ADAPTER("karmaGWT", "karma-gwt");

    private final String myResourceName;
    private final String myInstallName;

    Resource(String resourceName) {
      this(resourceName, resourceName);
    }

    Resource(String resourceName, String installName) {
      myResourceName = resourceName;
      myInstallName = installName;
    }
  }

  private enum TestBrowser {
    CHROME("Chrome"), PHANTOM("PhantomJS");

    private final String myConfigValue;

    TestBrowser(String configValue) {
      myConfigValue = configValue;
    }
  }

  private static final Pattern BASE_PATH = Pattern.compile("%BASE_PATH%");
  private static final Pattern TEST_MODULE = Pattern.compile("'%TEST_MODULE%'");
  private static final Pattern TEST_BROWSER = Pattern.compile("%TEST_BROWSER%");

  @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
  private File outputDirectory;

  @Parameter(defaultValue = "${project.artifactId}")
  private String projectArtifactId;

  @Parameter(defaultValue = "${project.version}")
  private String projectVersion;

  @Parameter(property="testRunner")
  private String testRunner;

  @Parameter(property="testModules")
  private List<String> testModules;

  @Parameter(defaultValue="PHANTOM", property="testBrowsers")
  private TestBrowser testBrowser;

  @Parameter(property="configPath")
  private File configPath;

  @Parameter(property="basePath")
  private String basePath;

  @Parameter(property="karmaBin")
  private File karmaBin;

  private Path karmaSetupPath;

  private Path karmaConfig;

  public void execute()
      throws MojoExecutionException {

    initVars();
    runAction(this::setupKarma, "failed to install Karma");
    runAction(this::runKarma, "failed at karma");

  }

  private void initVars() throws MojoExecutionException {
    if (configPath == null && (testModules == null || testModules.isEmpty())) {
      throw new MojoExecutionException("either provide configurations path or testModules");
    }
    if (basePath == null) {
      if (testRunner != null) {
        basePath = outputDirectory.toPath().resolve(testRunner).toString();
      } else {
        basePath = outputDirectory.toPath().resolve(projectArtifactId + "-" + projectVersion).toString();
      }
    }
    if (karmaBin == null) {
      karmaSetupPath = outputDirectory.toPath().getParent();
      karmaBin = karmaSetupPath.toFile();
    } else {
      karmaSetupPath = karmaBin.toPath();
    }
    karmaConfig = karmaSetupPath.resolve("karma.conf.js");
    validatePath(Paths.get(basePath).toAbsolutePath(), "basePath '" + basePath + "' doesn't exist");
    validatePath(karmaSetupPath.toAbsolutePath(), "karmaSetupPath '" + karmaSetupPath + "' doesn't exist");
  }

  private String testModules() {
    return testModules.stream().map(testModule -> "'" + testModule + "'").collect(Collectors.joining(","));
  }

  private void validatePath(Path path, String errorMessage) throws MojoExecutionException {
    if (!path.toFile().exists()) {
      throw new MojoExecutionException(errorMessage);
    }
  }

  private boolean setupKarma() throws URISyntaxException, IOException, InterruptedException {
    if (configPath == null) {
      URI libs = this.getClass().getResource(Resource.LIB.myResourceName).toURI();
      processResources(libs, resource -> {
        try (InputStreamReader inputStreamReader = new InputStreamReader(Files.newInputStream(resource));
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
          List<String> lines = new ArrayList<>();
          for (String line = bufferedReader.readLine(); line != null; line = bufferedReader.readLine()) {
            line = TEST_MODULE.matcher(line).replaceAll(testModules());
            line = BASE_PATH.matcher(line).replaceAll(basePath);
            line = TEST_BROWSER.matcher(line).replaceAll(testBrowser.myConfigValue);
            lines.add(line);
          }
          String rFileName = resource.getFileName().toString();
          Files.write(karmaSetupPath.resolve(rFileName), lines, Charset.defaultCharset());
        }
      });
    } else {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(configPath.toPath())) {
        for (Path p : ds) {
          Files.copy(p, karmaSetupPath.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    return runProcess("npm", "install");
  }

  private boolean runProcess(String... command) throws IOException, InterruptedException {
    getLog().info(String.join(" ", Arrays.asList(command)));
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    Process installDepProcess = processBuilder.inheritIO().directory(karmaSetupPath.toFile()).start();
    return installDepProcess.waitFor() == 0;
  }

  private boolean runKarma() throws URISyntaxException, IOException, InterruptedException {
    Path targetDirectory = karmaSetupPath.resolve(Paths.get("node_modules", Resource.ADAPTER.myInstallName));
    Path karma = karmaSetupPath.resolve(Paths.get("node_modules", ".bin", "karma"));
    Files.createDirectories(targetDirectory);
    URI resourceDirectory = this.getClass().getResource(Resource.ADAPTER.myResourceName).toURI();
    processResources(resourceDirectory,
        resource -> Files.copy(resource, targetDirectory.resolve(resource.getFileName().toString()), REPLACE_EXISTING));
    return runProcess(karma.toAbsolutePath().toString(), "start", karmaConfig.toAbsolutePath().toString());
  }

  private interface ResourceProcessor {
    void process(Path resource) throws IOException;
  }

  private void processResources(URI contentUri, ResourceProcessor processor) throws URISyntaxException, IOException {
    try (
        FileSystem fs = FileSystems.newFileSystem(contentUri, Collections.emptyMap())
    ) {
      Path containerPath = fs.provider().getPath(contentUri);
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(containerPath)) {
        for (Path p : ds) {
          processor.process(p);
        }
      }
    }
  }

  private interface Action {
    boolean run() throws URISyntaxException, IOException, InterruptedException;
  }

  private void runAction(Action action, String errorMessage) throws MojoExecutionException {
    try {
      if (!action.run()) {
        throw new MojoExecutionException(errorMessage);
      }
    } catch (URISyntaxException | IOException | InterruptedException e) {
      e.printStackTrace();
      throw new MojoExecutionException(errorMessage);
    }
  }

}
