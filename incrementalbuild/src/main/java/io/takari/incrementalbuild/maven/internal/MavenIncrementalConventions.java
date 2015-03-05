package io.takari.incrementalbuild.maven.internal;

import java.io.File;

import javax.inject.Named;

import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;

/**
 * @author igor
 */
@Named
public class MavenIncrementalConventions {

  /**
   * Returns conventional location of MojoExecution incremental build state
   */
  public File getExecutionStateLocation(MavenProject project, MojoExecution execution) {
    File stateDirectory = getProjectStateLocation(project);
    String builderId = getExecutionId(execution);
    return new File(stateDirectory, builderId);
  }

  /**
   * Returns conventional MojoExecution identifier used by incremental build tools.
   */
  public String getExecutionId(MojoExecution execution) {
    PluginDescriptor pluginDescriptor = execution.getMojoDescriptor().getPluginDescriptor();
    StringBuilder builderId = new StringBuilder();
    builderId.append(pluginDescriptor.getGroupId()).append('_')
        .append(pluginDescriptor.getArtifactId());
    builderId.append('_').append(execution.getGoal()).append('_')
        .append(execution.getExecutionId());
    return builderId.toString();
  }

  /**
   * Returns conventional location of MavenProject incremental build state
   */
  public File getProjectStateLocation(MavenProject project) {
    return new File(project.getBuild().getDirectory(), "incremental");
  }
}
