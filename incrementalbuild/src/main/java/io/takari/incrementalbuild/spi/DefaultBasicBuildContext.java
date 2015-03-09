package io.takari.incrementalbuild.spi;

import io.takari.incrementalbuild.BasicBuildContext;
import io.takari.incrementalbuild.ResourceStatus;

import java.io.File;
import java.io.IOException;

public class DefaultBasicBuildContext extends AbstractBuildContext implements BasicBuildContext {

  public DefaultBasicBuildContext(BuildContextEnvironment configuration) {
    super(configuration);
  }

  @Override
  protected void finalizeContext() throws IOException {
    if (isProcessed()) {
      // delete all obsolete outputs
      for (File oldOutput : oldState.getOutputs()) {
        if (!state.isOutput(oldOutput)) {
          deleteOutput(oldOutput);
        }
      }
    } else {
      // carry-over all metadata
      for (Object resource : oldState.resourcesMap().keySet()) {
        state.putResource(resource, oldState.getResource(resource));
        state.setResourceMessages(resource, oldState.getResourceMessages(resource));
        state.setResourceAttributes(resource, oldState.getResourceAttributes(resource));
      }
    }
  }

  @Override
  public boolean isProcessingRequired() {
    if (isEscalated()) {
      return true;
    }
    for (Object resource : state.resourcesMap().keySet()) {
      if (!state.isOutput(resource) && getResourceStatus(resource) != ResourceStatus.UNMODIFIED) {
        return true;
      }
    }
    for (Object oldResource : oldState.resourcesMap().keySet()) {
      if (!oldState.isOutput(oldResource) && !state.isResource(oldResource)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public DefaultOutput processOutput(File outputFile) {
    return super.processOutput(outputFile);
  }

  @Override
  public DefaultResourceMetadata<File> registerInput(File inputFile) {
    return super.registerInput(inputFile);
  }

  @Override
  protected void assertAssociation(DefaultResource<?> resource, DefaultOutput output) {
    // this context does not track input/output association, so lets make it clear to the users
    throw new UnsupportedOperationException();
  }
}
