package io.takari.incrementalbuild.maven.internal;

import io.takari.incrementalbuild.BasicBuildContext;
import io.takari.incrementalbuild.Output;
import io.takari.incrementalbuild.ResourceMetadata;
import io.takari.incrementalbuild.spi.BuildContextConfiguration;
import io.takari.incrementalbuild.spi.DefaultBasicBuildContextImpl;

import java.io.File;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.maven.execution.scope.MojoExecutionScoped;

@Named(MavenBasicBuildContext.HINT)
@Singleton
public class MavenBasicBuildContext implements BasicBuildContext {
  public static final String HINT = "singleton";

  @Named
  @MojoExecutionScoped
  public static class MojoExecutionScopedBasicBuildContext extends DefaultBasicBuildContextImpl {
    @Inject
    public MojoExecutionScopedBasicBuildContext(BuildContextConfiguration configuration) {
      super(configuration);
    }
  }

  private final Provider<MojoExecutionScopedBasicBuildContext> provider;

  @Inject
  public MavenBasicBuildContext(Provider<MojoExecutionScopedBasicBuildContext> provider) {
    this.provider = provider;
  }

  @Override
  public ResourceMetadata<File> registerInput(File inputFile) {
    return provider.get().registerInput(inputFile);
  }

  @Override
  public boolean isProcessingRequired() {
    return provider.get().isProcessingRequired();
  }

  @Override
  public Output<File> processOutput(File outputFile) {
    return provider.get().processOutput(outputFile);
  }

}
