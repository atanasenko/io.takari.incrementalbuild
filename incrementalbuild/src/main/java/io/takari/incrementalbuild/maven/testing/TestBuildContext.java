package io.takari.incrementalbuild.maven.testing;

import io.takari.incrementalbuild.MessageSeverity;
import io.takari.incrementalbuild.maven.internal.MavenBuildContextFinalizer;
import io.takari.incrementalbuild.maven.internal.MavenIncrementalConventions;
import io.takari.incrementalbuild.maven.internal.MojoConfigurationDigester;
import io.takari.incrementalbuild.maven.internal.ProjectWorkspace;
import io.takari.incrementalbuild.spi.DefaultOutput;
import io.takari.incrementalbuild.workspace.MessageSink;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;

import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

// this is explicitly bound in IncrementalBuildRuntime.addGuiceModules
class TestBuildContext extends MavenBuildContextFinalizer {

  private final IncrementalBuildLog logger;

  @Inject
  public TestBuildContext(ProjectWorkspace workspace, MojoConfigurationDigester digester,
      MavenIncrementalConventions conventions, MavenProject project, MojoExecution execution,
      IncrementalBuildLog logger) throws IOException {
    super(workspace, (MessageSink) null, digester, conventions, project, execution);
    this.logger = logger;
  }

  @Override
  public DefaultOutput processOutput(File outputFile) {
    DefaultOutput output = super.processOutput(outputFile);
    logger.addRegisterOutput(output.getResource());
    return output;
  }

  @Override
  protected void deleteStaleOutput(File outputFile) throws IOException {
    logger.addDeletedOutput(outputFile);
    super.deleteStaleOutput(outputFile);
  }

  @Override
  protected void carryOverOutput(File outputFile) {
    logger.addCarryoverOutput(outputFile);
    super.carryOverOutput(outputFile);
  }

  @Override
  protected void log(Object resource, int line, int column, String message, MessageSeverity severity,
      Throwable cause) {
    logger.message(resource, line, column, message, severity, cause);
  }
}
