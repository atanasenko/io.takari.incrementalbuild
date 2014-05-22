package io.takari.incrementalbuild.spi;

import static io.takari.incrementalbuild.BuildContext.ResourceStatus.MODIFIED;
import static io.takari.incrementalbuild.BuildContext.ResourceStatus.NEW;
import static io.takari.incrementalbuild.BuildContext.ResourceStatus.UNMODIFIED;
import io.takari.incrementalbuild.BuildContext.InputMetadata;
import io.takari.incrementalbuild.BuildContext.OutputMetadata;
import io.takari.incrementalbuild.BuildContext.ResourceStatus;
import io.takari.incrementalbuild.BuildContext.Severity;

import java.io.File;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

public class DefaultBuildContextTest {

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private DefaultBuildContext<?> newBuildContext() {
    return newBuildContext(Collections.<String, Serializable>emptyMap());
  }

  private DefaultBuildContext<?> newBuildContext(Map<String, Serializable> config) {
    File stateFile = new File(temp.getRoot(), "buildstate.ctx");
    return new TestBuildContext(stateFile, config);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRegisterInput_inputFileDoesNotExist() throws Exception {
    File file = new File("target/does_not_exist");
    Assert.assertTrue(!file.exists() && !file.canRead());
    newBuildContext().registerInput(file);
  }

  @Test
  public void testRegisterInput() throws Exception {
    // this is NOT part of API but rather currently implemented behaviour
    // API allows #registerInput return different instances

    File file = new File("src/test/resources/simplelogger.properties");
    Assert.assertTrue(file.exists() && file.canRead());
    DefaultBuildContext<?> context = newBuildContext();
    Assert.assertNotNull(context.registerInput(file));
    Assert.assertNotNull(context.registerInput(file));
  }

  @Test
  public void testOutputWithoutInputs() throws Exception {
    DefaultBuildContext<?> context = newBuildContext();

    File outputFile = temp.newFile("output_without_inputs");
    context.processOutput(outputFile);

    // is not deleted by repeated deleteStaleOutputs
    context.deleteStaleOutputs(true);
    Assert.assertTrue(outputFile.canRead());
    context.deleteStaleOutputs(true);
    Assert.assertTrue(outputFile.canRead());

    // is not deleted by commit
    context.commit();
    Assert.assertTrue(outputFile.canRead());

    // is not deleted after rebuild with re-registration
    context = newBuildContext();
    context.processOutput(outputFile);
    context.commit();
    Assert.assertTrue(outputFile.canRead());

    // deleted after rebuild without re-registration
    context = newBuildContext();
    context.commit();
    Assert.assertFalse(outputFile.canRead());
  }

  @Test
  public void testDeleteStaleOutputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    // deleted input
    Assert.assertTrue(inputFile.delete());
    context = newBuildContext();
    Assert.assertEquals(1, toList(context.deleteStaleOutputs(true)).size());
    Assert.assertFalse(outputFile.canRead());
    // same output can be deleted only once
    Assert.assertEquals(0, toList(context.deleteStaleOutputs(true)).size());

    context.commit();

    // deleted outputs are not carried over
    context = newBuildContext();
    Assert.assertEquals(0, toList(context.deleteStaleOutputs(true)).size());
  }

  private static <T> List<T> toList(Iterable<T> iterable) {
    if (iterable == null) {
      return null;
    }

    List<T> result = new ArrayList<T>();
    for (T t : iterable) {
      result.add(t);
    }
    return result;
  }

  @Test
  public void testDeleteStaleOutputs_inputProcessingPending() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    // modify input
    Files.append("test", inputFile, Charsets.UTF_8); // TODO does not matter
    context = newBuildContext();
    input = context.registerInput(inputFile).process();
    context.processInput(input);

    // input was modified and registered for processing
    // but actual processing has not happened yet
    // there is no association between (new) input and (old) output
    // assume the (old) output is orphaned and delete it
    // (new) output will be generate as needed when (new) input is processed
    context.deleteStaleOutputs(true);
    Assert.assertFalse(outputFile.canRead());
  }

  @Test
  public void testDeleteStaleOutputs_retainCarriedOverOutputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    context.deleteStaleOutputs(true);
    Assert.assertTrue(outputFile.canRead());
    Assert.assertEquals(1, toList(metadata.getAssociatedOutputs()).size());
    context.commit();

    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertTrue(outputFile.canRead());
    Assert.assertEquals(1, toList(metadata.getAssociatedOutputs()).size());
    context.commit();
  }

  @Test
  public void testDeleteStaleOutputs_nonEager() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    context = newBuildContext();
    input = context.registerInput(inputFile).process();

    // stale output is preserved during non-eager delete
    Assert.assertEquals(0, toList(context.deleteStaleOutputs(false)).size());
    Assert.assertTrue(outputFile.canRead());

    // stale output is removed during commit after non-eager delete
    context.commit();
    Assert.assertFalse(outputFile.canRead());
  }

  @Test
  public void testDeleteStaleOutputs_noAssociatedInputs() throws Exception {
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    context.processOutput(outputFile);
    context.commit();

    context = newBuildContext();
    // outputs without inputs may or may not be recreated and should be retained by non-eager delete
    Assert.assertEquals(0, toList(context.deleteStaleOutputs(false)).size());
    Assert.assertTrue(outputFile.canRead());

    // stale output is removed during commit after non-eager delete
    context.commit();
    Assert.assertFalse(outputFile.canRead());
  }

  @Test
  public void testGetInputStatus() throws Exception {
    File inputFile = temp.newFile("inputFile");

    // initial build
    DefaultBuildContext<?> context = newBuildContext();
    // first time invocation returns Input for processing
    Assert.assertEquals(NEW, context.registerInput(inputFile).getStatus());
    // second invocation still returns NEW
    Assert.assertEquals(NEW, context.registerInput(inputFile).getStatus());
    context.commit();

    // new build
    context = newBuildContext();
    // input file was not modified since last build
    Assert.assertEquals(UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    // new build
    Files.append("test", inputFile, Charsets.UTF_8);
    context = newBuildContext();
    // input file was modified since last build
    Assert.assertEquals(MODIFIED, context.registerInput(inputFile).getStatus());
  }

  @Test
  public void testGetInputStatus_associatedOutput() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    // initial build
    DefaultBuildContext<?> context = newBuildContext();
    // first time invocation returns Input for processing
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    // no-change rebuild
    context = newBuildContext();
    Assert.assertEquals(UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    // rebuild after output changed
    Files.append("test", outputFile, Charsets.UTF_8);
    context = newBuildContext();
    Assert.assertEquals(MODIFIED, context.registerInput(inputFile).getStatus());
    context.registerInput(inputFile).process().associateOutput(outputFile);
    context.commit();

    // no-change rebuild
    context = newBuildContext();
    Assert.assertEquals(UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    // rebuild after output delete
    Assert.assertTrue(outputFile.delete());
    context = newBuildContext();
    Assert.assertEquals(MODIFIED, context.registerInput(inputFile).getStatus());
  }

  @Test
  public void testGetInputStatus_includedInputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File includedFile = temp.newFile("includedFile");

    // initial build
    DefaultBuildContext<?> context = newBuildContext();
    // first time invocation returns Input for processing
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateIncludedInput(includedFile);
    context.commit();

    // no-change rebuild
    context = newBuildContext();
    Assert.assertEquals(UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    // rebuild after output changed
    Files.append("test", includedFile, Charsets.UTF_8);
    context = newBuildContext();
    Assert.assertEquals(MODIFIED, context.registerInput(inputFile).getStatus());
    context.registerInput(inputFile).process().associateIncludedInput(includedFile);
    context.commit();

    // no-change rebuild
    context = newBuildContext();
    Assert.assertEquals(UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    // rebuild after output delete
    Assert.assertTrue(includedFile.delete());
    context = newBuildContext();
    Assert.assertEquals(MODIFIED, context.registerInput(inputFile).getStatus());
  }

  @Test(expected = IllegalStateException.class)
  public void testInputModifiedAfterRegistration() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    context = newBuildContext();
    context.registerInput(inputFile);

    // this is incorrect use of build-avoidance API
    // input has changed after it was registered for processing
    // IllegalStateException is raised to prevent unexpected process/not-process flip-flop
    Files.append("test", inputFile, Charsets.UTF_8);
    context.commit();
    Assert.assertTrue(outputFile.canRead());
  }

  @Test
  public void testCommit_orphanedOutputsCleanup() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    context.commit();

    // input is not part of input set any more
    // associated output must be cleaned up
    context = newBuildContext();
    context.commit();
    Assert.assertFalse(outputFile.canRead());
  }

  @Test
  public void testCommit_staleOutputCleanup() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile1 = temp.newFile("outputFile1");
    File outputFile2 = temp.newFile("outputFile2");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile1);
    input.associateOutput(outputFile2);
    context.commit();

    context = newBuildContext();
    input = context.registerInput(inputFile).process();
    context.processInput(input);
    input.associateOutput(outputFile1);
    context.commit();
    Assert.assertFalse(outputFile2.canRead());

    context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    Assert.assertEquals(1, toList(metadata.getAssociatedOutputs()).size());
    context.commit();
  }

  @Test
  public void testCreateStateParentDirectory() throws Exception {
    File stateFile = new File(temp.getRoot(), "sub/dir/buildstate.ctx");
    TestBuildContext context =
        new TestBuildContext(stateFile, Collections.<String, Serializable>emptyMap());
    context.commit();
    Assert.assertTrue(stateFile.canRead());
  }

  @Test
  public void testIncludedInputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File includedFile = temp.newFile("includedFile");
    Files.append("test", includedFile, Charsets.UTF_8);

    DefaultBuildContext<?> context = newBuildContext();
    context.registerInput(inputFile).process().associateIncludedInput(includedFile);
    context.commit();

    context = newBuildContext();
    Assert.assertEquals(ResourceStatus.UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    // check state carry over
    context = newBuildContext();
    Assert.assertEquals(ResourceStatus.UNMODIFIED, context.registerInput(inputFile).getStatus());
    context.commit();

    Files.append("test", includedFile, Charsets.UTF_8);

    context = newBuildContext();
    Assert.assertEquals(ResourceStatus.MODIFIED, context.registerInput(inputFile).getStatus());
  }

  @Test
  public void testGetDependentInputs_deletedInput() throws Exception {
    File inputFile = temp.newFile("inputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.addRequirement("a", "b");
    context.commit();

    // delete the input
    Assert.assertTrue(inputFile.delete());

    // the input does not exist and therefore does not require the capability
    context = newBuildContext();
    Assert.assertEquals(0, toList(context.getDependentInputs("a", "b")).size());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRegisterInput_nullInput() throws Exception {
    newBuildContext().registerInput((File) null);
  }

  @Test
  public void testRegisterAndProcessInputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    List<DefaultInput<File>> inputs =
        toList(context.registerAndProcessInputs(Arrays.asList(inputFile)));
    Assert.assertEquals(1, inputs.size());
    Assert.assertEquals(ResourceStatus.NEW, inputs.get(0).getStatus());
    inputs.get(0).associateOutput(outputFile);
    context.commit();

    // no change rebuild
    context = newBuildContext();
    inputs = toList(context.registerAndProcessInputs(Arrays.asList(inputFile)));
    Assert.assertEquals(0, inputs.size());
    context.commit();
  }

  @Test
  public void testGetAssociatedOutputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    context.registerInput(inputFile).process().associateOutput(outputFile);
    context.commit();

    //
    context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    List<? extends OutputMetadata<File>> outputs = toList(metadata.getAssociatedOutputs());
    Assert.assertEquals(1, outputs.size());
    Assert.assertEquals(ResourceStatus.UNMODIFIED, outputs.get(0).getStatus());
    context.commit();

    //
    Files.append("test", outputFile, Charsets.UTF_8);
    newBuildContext();
    metadata = context.registerInput(inputFile);
    outputs = toList(metadata.getAssociatedOutputs());
    Assert.assertEquals(1, outputs.size());
    Assert.assertEquals(ResourceStatus.MODIFIED, outputs.get(0).getStatus());
    context.commit();

    //
    Assert.assertTrue(outputFile.delete());
    newBuildContext();
    metadata = context.registerInput(inputFile);
    outputs = toList(metadata.getAssociatedOutputs());
    Assert.assertEquals(1, outputs.size());
    Assert.assertEquals(ResourceStatus.REMOVED, outputs.get(0).getStatus());
    context.commit();
  }

  @Test
  public void testGetRegisteredInputs() throws Exception {
    File inputFile1 = temp.newFile("inputFile1");
    File inputFile2 = temp.newFile("inputFile2");
    File inputFile3 = temp.newFile("inputFile3");
    File inputFile4 = temp.newFile("inputFile4");

    DefaultBuildContext<?> context = newBuildContext();
    inputFile1 = context.registerInput(inputFile1).getResource();
    inputFile2 = context.registerInput(inputFile2).getResource();
    inputFile3 = context.registerInput(inputFile3).getResource();
    context.commit();

    Files.append("test", inputFile3, Charsets.UTF_8);

    context = newBuildContext();

    // context.registerInput(inputFile1); DELETED
    context.registerInput(inputFile2); // UNMODIFIED
    context.registerInput(inputFile3); // MODIFIED
    inputFile4 = context.registerInput(inputFile4).getResource(); // NEW

    Map<File, InputMetadata<File>> inputs = new TreeMap<File, InputMetadata<File>>();
    for (InputMetadata<File> input : context.getRegisteredInputs()) {
      inputs.put(input.getResource(), input);
    }

    Assert.assertEquals(4, inputs.size());
    Assert.assertEquals(ResourceStatus.REMOVED, inputs.get(inputFile1).getStatus());
    Assert.assertEquals(ResourceStatus.UNMODIFIED, inputs.get(inputFile2).getStatus());
    Assert.assertEquals(ResourceStatus.MODIFIED, inputs.get(inputFile3).getStatus());
    Assert.assertEquals(ResourceStatus.NEW, inputs.get(inputFile4).getStatus());
  }

  @Test
  public void testGetRemovedInputs() throws Exception {
    File inputFile1 = temp.newFile("inputFile1");
    File inputFile2 = temp.newFile("inputFile2");

    DefaultBuildContext<?> context = newBuildContext();
    inputFile1 = context.registerInput(inputFile1).getResource();
    inputFile2 = context.registerInput(inputFile2).getResource();
    context.commit();

    context = newBuildContext();
    context.registerInput(inputFile1); // UNMODIFIED
    List<DefaultInputMetadata<File>> removed = toList(context.getRemovedInputs(File.class));
    Assert.assertEquals(1, removed.size());
    Assert.assertEquals(inputFile2, removed.get(0).getResource());
  }

  @Test
  public void testGetProcessedOutputs() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile1 = temp.newFile("outputFile1");
    File outputFile2 = temp.newFile("outputFile2");
    File outputFile3 = temp.newFile("outputFile3");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    outputFile1 = input.associateOutput(outputFile1).getResource();
    outputFile2 = input.associateOutput(outputFile2).getResource();
    Map<File, OutputMetadata<File>> outputs = toMap(context.getProcessedOutputs());
    Assert.assertEquals(2, outputs.size());
    Assert.assertNotNull(outputs.get(outputFile1));
    Assert.assertNotNull(outputs.get(outputFile2));
    context.commit();

    // no-change carry-over
    context = newBuildContext();
    context.registerInput(inputFile);
    outputs = toMap(context.getProcessedOutputs());
    Assert.assertEquals(2, outputs.size());
    Assert.assertNotNull(outputs.get(outputFile1));
    Assert.assertNotNull(outputs.get(outputFile2));
    context.commit();

    // one more time, just to make sure carry-over does not decay
    context = newBuildContext();
    context.registerInput(inputFile);
    outputs = toMap(context.getProcessedOutputs());
    Assert.assertEquals(2, outputs.size());
    Assert.assertNotNull(outputs.get(outputFile1));
    Assert.assertNotNull(outputs.get(outputFile2));
    context.commit();

    // drop one output, introduce another
    context = newBuildContext();
    input = context.registerInput(inputFile).process();
    outputFile1 = input.associateOutput(outputFile1).getResource();
    outputFile3 = input.associateOutput(outputFile3).getResource();
    outputs = toMap(context.getProcessedOutputs());
    Assert.assertEquals(2, outputs.size());
    Assert.assertNotNull(outputs.get(outputFile1));
    Assert.assertNotNull(outputs.get(outputFile3));
    context.commit();

    // dropped output is not carried over
    context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    Assert.assertEquals(2, toMap(metadata.getAssociatedOutputs()).size());
    outputs = toMap(context.getProcessedOutputs());
    Assert.assertEquals(2, outputs.size());
    Assert.assertNotNull(outputs.get(outputFile1));
    Assert.assertNotNull(outputs.get(outputFile3));
    context.commit();
  }

  @Test
  public void testOutputWithoutInputs_carryOver() throws Exception {
    File outputFile = temp.newFile("output_without_inputs");
    DefaultBuildContext<?> context = newBuildContext();

    DefaultOutput output = context.processOutput(outputFile);
    outputFile = output.getResource();
    output.setValue("key", "value");
    context.commit();

    context = newBuildContext();
    List<DefaultOutputMetadata> outputs = toList(context.getProcessedOutputs());
    Assert.assertEquals(1, outputs.size());
    Assert.assertEquals(outputFile, outputs.get(0).getResource());
    Assert.assertEquals("value", outputs.get(0).getValue("key", String.class));
    context.carryOverOutput(outputs.get(0).getResource());
    context.commit();
    Assert.assertTrue(outputFile.canRead());

    context = newBuildContext();
    outputs = toList(context.getProcessedOutputs());
    Assert.assertEquals(1, outputs.size());
    Assert.assertEquals(outputFile, outputs.get(0).getResource());
    Assert.assertEquals("value", outputs.get(0).getValue("key", String.class));
    context.commit();
    Assert.assertFalse(outputFile.canRead());
  }

  private <T> Map<T, OutputMetadata<T>> toMap(Iterable<? extends OutputMetadata<T>> elements) {
    Map<T, OutputMetadata<T>> result = new TreeMap<T, OutputMetadata<T>>();
    for (OutputMetadata<T> element : elements) {
      result.put(element.getResource(), element);
    }
    return result;
  }

  @Test
  public void testInputAttributes() throws Exception {
    File inputFile = temp.newFile("inputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    Assert.assertNull(metadata.getValue("key", String.class));
    DefaultInput<File> input = metadata.process();
    Assert.assertNull(input.setValue("key", "value"));
    context.commit();

    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertEquals("value", metadata.getValue("key", String.class));
    context.commit();

    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertEquals("value", metadata.getValue("key", String.class));
    input = metadata.process();
    Assert.assertNull(input.getValue("key", String.class));
    Assert.assertEquals("value", input.setValue("key", "newValue"));
    Assert.assertEquals("value", input.setValue("key", "newValue"));
    Assert.assertEquals("newValue", input.getValue("key", String.class));
    context.commit();

    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertEquals("newValue", metadata.getValue("key", String.class));
    context.commit();
  }

  @Test
  public void testOutputAttributes() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultOutput output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertNull(output.setValue("key", "value"));
    context.commit();

    // no change output, attributes should be carried over as-is
    context = newBuildContext();
    context.registerInput(inputFile);
    OutputMetadata<File> metadata = context.getProcessedOutputs().iterator().next();
    Assert.assertEquals("value", metadata.getValue("key", String.class));
    context.commit();

    context = newBuildContext();
    output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertNull(output.getValue("key", String.class)); // no value during current build
    Assert.assertEquals("value", output.setValue("key", "newValue"));
    Assert.assertEquals("value", output.setValue("key", "newValue"));
    Assert.assertEquals("newValue", output.getValue("key", String.class));
    context.commit();
  }

  @Test
  public void testOutputStatus() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = new File(temp.getRoot(), "outputFile");

    Assert.assertFalse(outputFile.canRead());

    DefaultBuildContext<?> context = newBuildContext();
    DefaultOutput output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertEquals(ResourceStatus.NEW, output.getStatus());
    output.newOutputStream().close();
    context.commit();

    // no-change rebuild
    context = newBuildContext();
    output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertEquals(ResourceStatus.UNMODIFIED, output.getStatus());
    context.commit();

    // modified output
    Files.write("test", outputFile, Charsets.UTF_8);
    context = newBuildContext();
    output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertEquals(ResourceStatus.MODIFIED, output.getStatus());
    context.commit();

    // no-change rebuild
    context = newBuildContext();
    output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertEquals(ResourceStatus.UNMODIFIED, output.getStatus());
    context.commit();

    // deleted output
    Assert.assertTrue(outputFile.delete());
    context = newBuildContext();
    output = context.registerInput(inputFile).process().associateOutput(outputFile);
    Assert.assertEquals(ResourceStatus.REMOVED, output.getStatus());
    output.newOutputStream().close(); // processed outputs must exit or commit fails
    context.commit();
  }

  @Test
  public void testStateSerialization_useTCCL() throws Exception {
    File inputFile = temp.newFile("inputFile");

    DefaultBuildContext<?> context = newBuildContext();

    URL dummyJar = new File("src/test/projects/dummy/dummy-1.0.jar").toURI().toURL();
    ClassLoader tccl = new URLClassLoader(new URL[] {dummyJar});
    ClassLoader origTCCL = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(tccl);

      Object dummy = tccl.loadClass("dummy.Dummy").newInstance();

      DefaultInput<File> input = context.registerInput(inputFile).process();
      input.setValue("dummy", (Serializable) dummy);
      context.commit();

      context = newBuildContext();
      Assert.assertFalse(((TestBuildContext) context).isEscalated());
      Assert.assertNotNull(context.registerInput(inputFile).getValue("dummy", Serializable.class));
      // no commit
    } finally {
      Thread.currentThread().setContextClassLoader(origTCCL);
    }

    // sanity check, make sure empty state is loaded without proper TCCL
    context = newBuildContext();
    Assert.assertTrue(((TestBuildContext) context).isEscalated());
  }

  @Test
  public void testConfigurationChange() throws Exception {
    File inputFile = temp.newFile("input");
    File outputFile = temp.newFile("output");
    File looseOutputFile = temp.newFile("looseOutputFile");

    DefaultBuildContext<?> context = newBuildContext();
    context.registerInput(inputFile).process().associateOutput(outputFile);
    context.processOutput(looseOutputFile);
    context.commit();

    context =
        newBuildContext(Collections.<String, Serializable>singletonMap("config", "parameter"));
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    Assert.assertEquals(ResourceStatus.MODIFIED, metadata.getStatus());
    DefaultInput<File> input = metadata.process();
    Assert.assertEquals(ResourceStatus.MODIFIED, input.getStatus());
    DefaultOutput output = input.associateOutput(outputFile);
    Assert.assertEquals(ResourceStatus.MODIFIED, output.getStatus());
    DefaultOutput looseOutput = context.processOutput(looseOutputFile);
    Assert.assertEquals(ResourceStatus.MODIFIED, looseOutput.getStatus());
  }

  @Test
  public void testInputMessages() throws Exception {
    File inputFile = temp.newFile("inputFile");

    // initial message
    DefaultBuildContext<?> context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    inputFile = metadata.getResource();
    DefaultInput<File> input = metadata.process();
    input.addMessage(0, 0, "message", Severity.WARNING, null);
    context.commit();

    // the message is retained during no-change rebuild
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    List<Message> messages = toList(context.getMessages(inputFile));
    Assert.assertEquals(1, messages.size());
    Assert.assertEquals("message", messages.get(0).message);
    context.commit();

    // the message is retained during second no-change rebuild
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    messages = toList(context.getMessages(inputFile));
    Assert.assertEquals(1, messages.size());
    Assert.assertEquals("message", messages.get(0).message);
    context.commit();

    // new message
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    input = metadata.process();
    input.addMessage(0, 0, "newMessage", Severity.WARNING, null);
    context.commit();
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    messages = toList(context.getMessages(inputFile));
    Assert.assertEquals(1, messages.size());
    Assert.assertEquals("newMessage", messages.get(0).message);
    context.commit();

    // removed message
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    input = metadata.process();
    context.commit();
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertNull(context.getMessages(inputFile));
    context.commit();
  }

  @Test
  public void testOutputMessages() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File outputFile = temp.newFile("outputFile");

    // initial message
    DefaultBuildContext<?> context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    DefaultInput<File> input = metadata.process();
    DefaultOutput output = input.associateOutput(outputFile);
    output.addMessage(0, 0, "message", Severity.WARNING, null);
    context.commit();

    // the message is retained during no-change rebuild
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    OutputMetadata<File> outputMetadata = toList(metadata.getAssociatedOutputs()).get(0);
    List<Message> messages = toList(context.getMessages(outputMetadata.getResource()));
    Assert.assertEquals(1, messages.size());
    Assert.assertEquals("message", messages.get(0).message);
    context.commit();

    // the message is retained during second no-change rebuild
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    outputMetadata = toList(metadata.getAssociatedOutputs()).get(0);
    messages = toList(context.getMessages(outputMetadata.getResource()));
    Assert.assertEquals(1, messages.size());
    Assert.assertEquals("message", messages.get(0).message);
    context.commit();

    // new message
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    input = metadata.process();
    output = input.associateOutput(outputFile);
    output.addMessage(0, 0, "newMessage", Severity.WARNING, null);
    context.commit();
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    outputMetadata = toList(metadata.getAssociatedOutputs()).get(0);
    messages = toList(context.getMessages(outputMetadata.getResource()));
    Assert.assertEquals(1, messages.size());
    Assert.assertEquals("newMessage", messages.get(0).message);
    context.commit();

    // removed message
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    input = metadata.process();
    output = input.associateOutput(outputFile);
    context.commit();
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    outputMetadata = toList(metadata.getAssociatedOutputs()).get(0);
    Assert.assertNull(context.getMessages(outputMetadata.getResource()));
    context.commit();
  }

  @Test
  public void testIsProcessingRequired() throws Exception {
    File inputFile = temp.newFile("inputFile");
    File includedInputFile = temp.newFile("included_input_file");
    File outputFile = temp.newFile("outputFile");
    File outputWithoutInput = temp.newFile("output_without_inputs");

    DefaultBuildContext<?> context = newBuildContext();
    DefaultInput<File> input = context.registerInput(inputFile).process();
    input.associateOutput(outputFile);
    input.associateIncludedInput(includedInputFile);
    context.processOutput(outputWithoutInput);
    context.commit();

    // no change
    context = newBuildContext();
    context.registerInput(inputFile);
    Assert.assertFalse(context.isProcessingRequired());

    // processed input
    context = newBuildContext();
    context.registerInput(inputFile).process().associateIncludedInput(includedInputFile);
    Assert.assertTrue(context.isProcessingRequired());

    // processed output
    context = newBuildContext();
    context.registerInput(inputFile);
    context.processOutput(outputWithoutInput);
    Assert.assertTrue(context.isProcessingRequired());

    // removed input
    context = newBuildContext();
    Assert.assertTrue(context.isProcessingRequired());

    // modified input
    Files.append("test", inputFile, Charsets.UTF_8);
    context = newBuildContext();
    DefaultInputMetadata<File> metadata = context.registerInput(inputFile);
    Assert.assertTrue(context.isProcessingRequired());
    input = metadata.process();
    input.associateOutput(outputFile);
    input.associateIncludedInput(includedInputFile);
    context.processOutput(outputWithoutInput);
    context.commit();

    // modified output
    Files.append("test", outputFile, Charsets.UTF_8);
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertTrue(context.isProcessingRequired());
    input = metadata.process();
    input.associateOutput(outputFile);
    input.associateIncludedInput(includedInputFile);
    context.processOutput(outputWithoutInput);
    context.commit();

    // modified output without inputs
    Files.append("test", outputWithoutInput, Charsets.UTF_8);
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertTrue(context.isProcessingRequired());
    input = metadata.process();
    input.associateOutput(outputFile);
    input.associateIncludedInput(includedInputFile);
    context.processOutput(outputWithoutInput);
    context.commit();

    // modified included input
    Files.append("test", includedInputFile, Charsets.UTF_8);
    context = newBuildContext();
    metadata = context.registerInput(inputFile);
    Assert.assertTrue(context.isProcessingRequired());
    input = metadata.process();
    input.associateOutput(outputFile);
    input.associateIncludedInput(includedInputFile);
    context.processOutput(outputWithoutInput);
    context.commit();

    // configuration changed
    context = newBuildContext(Collections.<String, Serializable>singletonMap("test", "modified"));
    metadata = context.registerInput(inputFile);
    Assert.assertTrue(context.isProcessingRequired());

  }

  @Test
  public void testExplicitBuildEscalation() throws Exception {
    Map<String, Serializable> config = new HashMap<String, Serializable>();
    config.put("test", "value");
    DefaultBuildContext<?> context = newBuildContext(config);
    context.commit();

    config.put(DefaultBuildContext.CONFIG_ESCALATED, "true");
    context = newBuildContext(config);
    Assert.assertTrue(context.isEscalated());
    context.commit();

    config.put(DefaultBuildContext.CONFIG_ESCALATED, "false");
    context = newBuildContext(config);
    Assert.assertFalse(context.isEscalated());
    context.commit();

    config.remove(DefaultBuildContext.CONFIG_ESCALATED);
    context = newBuildContext(config);
    Assert.assertFalse(context.isEscalated());
    context.commit();
  }
}
