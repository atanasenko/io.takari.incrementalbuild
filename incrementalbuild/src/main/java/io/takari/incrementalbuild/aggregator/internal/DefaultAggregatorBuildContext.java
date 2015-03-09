package io.takari.incrementalbuild.aggregator.internal;


import io.takari.incrementalbuild.Resource;
import io.takari.incrementalbuild.ResourceMetadata;
import io.takari.incrementalbuild.ResourceStatus;
import io.takari.incrementalbuild.aggregator.AggregateCreator;
import io.takari.incrementalbuild.aggregator.AggregateInput;
import io.takari.incrementalbuild.aggregator.AggregatorBuildContext;
import io.takari.incrementalbuild.aggregator.InputProcessor;
import io.takari.incrementalbuild.aggregator.MetadataAggregateCreator;
import io.takari.incrementalbuild.spi.AbstractBuildContext;
import io.takari.incrementalbuild.spi.BuildContextEnvironment;
import io.takari.incrementalbuild.spi.DefaultBuildContextState;
import io.takari.incrementalbuild.spi.DefaultOutput;
import io.takari.incrementalbuild.spi.DefaultResource;
import io.takari.incrementalbuild.workspace.Workspace2;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DefaultAggregatorBuildContext extends AbstractBuildContext
    implements
      AggregatorBuildContext {

  private static final String KEY_METADATA = "aggregate.metadata";

  private final Map<File, File> inputBasedir = new HashMap<>();

  private final Map<File, Map<String, Serializable>> outputMetadata = new HashMap<>();

  public DefaultAggregatorBuildContext(BuildContextEnvironment configuration) {
    super(configuration);
  }

  @Override
  public DefaultAggregateMetadata registerOutput(File outputFile) {
    outputFile = normalize(outputFile);
    if (isRegisteredResource(outputFile)) {
      // only allow single registrator of the same output. not sure why/if multuple will be needed
      throw new IllegalStateException("Output already registrered " + outputFile);
    }
    registerNormalizedOutput(outputFile);
    return new DefaultAggregateMetadata(this, oldState, outputFile);
  }

  public void associateInputs(DefaultAggregateMetadata output, File basedir,
      Collection<String> includes, Collection<String> excludes, InputProcessor... processors)
      throws IOException {
    if (processors.length > 1) {
      throw new IllegalArgumentException();
    }

    basedir = normalize(basedir);

    File outputFile = output.getResource();

    Map<String, Serializable> metadata = outputMetadata.get(outputFile);
    if (metadata == null) {
      metadata = new HashMap<>();
      outputMetadata.put(outputFile, metadata);
    }

    for (ResourceMetadata<File> inputMetadata : registerInputs(basedir, includes, excludes)) {
      File resource = inputMetadata.getResource();
      inputBasedir.put(resource, basedir); // TODO move to FileState
      if (getResourceStatus(resource) != ResourceStatus.UNMODIFIED) {
        if (isProcessedResource(resource)) {
          // don't know all implications, will deal when/if anyone asks for it
          throw new IllegalStateException("Input already processed " + resource);
        }
        Resource<File> input = inputMetadata.process();
        if (processors != null) {
          for (InputProcessor processor : processors) {
            Map<String, Serializable> processed = processor.process(input);
            if (processed != null) {
              state.putResourceAttribute(resource, KEY_METADATA, new HashMap<>(processed));
              metadata.putAll(processed);
            }
          }
        }
      } else {
        Map<String, Serializable> attributes = oldState.getResourceAttributes(resource);
        state.setResourceAttributes(resource, attributes);
        if (attributes != null) {
          @SuppressWarnings("unchecked")
          HashMap<String, Serializable> persisted =
              (HashMap<String, Serializable>) attributes.get(KEY_METADATA);
          if (persisted != null) {
            metadata.putAll(persisted);
          }
        }
      }
      state.putResourceOutput(resource, output.getResource());
    }
  }

  public boolean createIfNecessary(DefaultAggregateMetadata outputMetadata, AggregateCreator creator)
      throws IOException {
    File outputFile = outputMetadata.getResource();
    boolean processingRequired = isEscalated();
    if (!processingRequired) {
      processingRequired = isProcessingRequired(outputFile);
    }
    if (processingRequired) {
      Collection<Object> inputFiles = state.getOutputInputs(outputFile);
      DefaultOutput output = processOutput(outputFile);
      List<AggregateInput> inputs = new ArrayList<>();
      if (inputFiles != null) {
        for (Object inputFile : inputFiles) {
          if (!isProcessedResource(inputFile)) {
            processResource(inputFile);
          }
          state.putResourceOutput(inputFile, outputFile);
          inputs.add(newAggregateInput((File) inputFile, true /* processed */));
        }
      }
      creator.create(output, inputs);
    } else {
      markUptodateOutput(outputFile);
    }
    return processingRequired;
  }

  private DefaultAggregateInput newAggregateInput(File inputFile, boolean processed) {
    DefaultBuildContextState state;
    if (processed) {
      state = isProcessedResource(inputFile) ? this.state : this.oldState;
    } else {
      state = this.oldState;
    }
    return new DefaultAggregateInput(this, state, inputBasedir.get(inputFile), inputFile);
  }

  // re-create output if any its inputs were added, changed or deleted since previous build
  private boolean isProcessingRequired(File outputFile) {
    Collection<Object> inputs = state.getOutputInputs(outputFile);
    if (inputs != null) {
      for (Object input : inputs) {
        if (getResourceStatus(input) != ResourceStatus.UNMODIFIED) {
          return true;
        }
      }
    }

    Collection<Object> oldInputs = oldState.getOutputInputs(outputFile);
    if (oldInputs != null) {
      for (Object oldInput : oldInputs) {
        if (!inputs.contains(oldInput)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  protected void assertAssociation(DefaultResource<?> resource, DefaultOutput output) {
    // aggregating context supports any association between inputs and outputs
    // or, rather, there is no obviously wrong combination
  }

  public boolean createIfNecessary(DefaultAggregateMetadata outputMetadata,
      MetadataAggregateCreator creator) throws IOException {
    File outputFile = outputMetadata.getResource();
    Map<String, Serializable> metadata = this.outputMetadata.get(outputFile);
    boolean processingRequired = isEscalated();
    if (!processingRequired) {
      HashMap<String, Serializable> oldMetadata = new HashMap<>();
      Collection<Object> oldInputs = oldState.getOutputInputs(outputFile);
      if (oldInputs != null) {
        for (Object input : oldInputs) {
          putAll(oldMetadata, oldState.getResourceAttribute(input, KEY_METADATA));
        }
      }
      processingRequired = !Objects.equals(metadata, oldMetadata);
    }
    if (processingRequired) {
      DefaultOutput output = processOutput(outputFile);
      Collection<Object> inputs = state.getOutputInputs(outputFile);
      if (inputs != null) {
        for (Object input : inputs) {
          state.putResourceOutput(input, outputFile);
        }
      }
      creator.create(output, metadata);
    } else {
      markUptodateOutput(outputFile);
    }
    return processingRequired;
  }

  @SuppressWarnings("unchecked")
  private <K, V> void putAll(Map<K, V> target, Serializable source) {
    if (source != null) {
      target.putAll((Map<K, V>) source);
    }
  }

  @Override
  protected void finalizeContext() throws IOException {
    for (File oldOutput : oldState.getOutputs()) {
      if (isProcessedResource(oldOutput)) {
        // processed during this build
      } else if (state.getResource(oldOutput) == null) {
        // registered but neither processed or marked as up-to-date
        deleteOutput(oldOutput);
      } else {
        // up-to-date
        state.setResourceMessages(oldOutput, oldState.getResourceMessages(oldOutput));
        state.setResourceAttributes(oldOutput, oldState.getResourceAttributes(oldOutput));
        if (workspace instanceof Workspace2) {
          ((Workspace2) workspace).carryOverOutput(oldOutput);
        }
      }
    }
  }
}
