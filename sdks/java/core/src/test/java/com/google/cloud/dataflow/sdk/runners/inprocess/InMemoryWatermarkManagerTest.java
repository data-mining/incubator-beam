/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.runners.inprocess;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.google.cloud.dataflow.sdk.runners.inprocess.InMemoryWatermarkManager.FiredTimers;
import com.google.cloud.dataflow.sdk.runners.inprocess.InMemoryWatermarkManager.TimerUpdate;
import com.google.cloud.dataflow.sdk.runners.inprocess.InMemoryWatermarkManager.TimerUpdate.TimerUpdateBuilder;
import com.google.cloud.dataflow.sdk.runners.inprocess.InMemoryWatermarkManager.TransformWatermarks;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.UncommittedBundle;
import com.google.cloud.dataflow.sdk.testing.TestPipeline;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Filter;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.WithKeys;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.TimeDomain;
import com.google.cloud.dataflow.sdk.util.TimerInternals.TimerData;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionList;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.cloud.dataflow.sdk.values.TimestampedValue;
import com.google.common.collect.ImmutableList;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.joda.time.Instant;
import org.joda.time.ReadableInstant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link InMemoryWatermarkManager}.
 */
@RunWith(JUnit4.class)
public class InMemoryWatermarkManagerTest implements Serializable {
  private transient MockClock clock;

  private transient PCollection<Integer> createdInts;

  private transient PCollection<Integer> filtered;
  private transient PCollection<Integer> filteredTimesTwo;
  private transient PCollection<KV<String, Integer>> keyed;

  private transient PCollection<Integer> intsToFlatten;
  private transient PCollection<Integer> flattened;

  private transient InMemoryWatermarkManager manager;

  @Before
  public void setup() {
    TestPipeline p = TestPipeline.create();

    createdInts = p.apply("createdInts", Create.of(1, 2, 3));

    filtered = createdInts.apply("filtered", Filter.greaterThan(1));
    filteredTimesTwo = filtered.apply("timesTwo", ParDo.of(new DoFn<Integer, Integer>() {
      @Override
      public void processElement(DoFn<Integer, Integer>.ProcessContext c) throws Exception {
        c.output(c.element() * 2);
      }
    }));

    keyed = createdInts.apply("keyed", WithKeys.<String, Integer>of("MyKey"));

    intsToFlatten = p.apply("intsToFlatten", Create.of(-1, 256, 65535));
    PCollectionList<Integer> preFlatten = PCollectionList.of(createdInts).and(intsToFlatten);
    flattened = preFlatten.apply("flattened", Flatten.<Integer>pCollections());

    Collection<AppliedPTransform<?, ?, ?>> rootTransforms =
        ImmutableList.<AppliedPTransform<?, ?, ?>>of(
            createdInts.getProducingTransformInternal(),
            intsToFlatten.getProducingTransformInternal());

    Map<PValue, Collection<AppliedPTransform<?, ?, ?>>> consumers = new HashMap<>();
    consumers.put(
        createdInts,
        ImmutableList.<AppliedPTransform<?, ?, ?>>of(filtered.getProducingTransformInternal(),
            keyed.getProducingTransformInternal(), flattened.getProducingTransformInternal()));
    consumers.put(
        filtered,
        Collections.<AppliedPTransform<?, ?, ?>>singleton(
            filteredTimesTwo.getProducingTransformInternal()));
    consumers.put(filteredTimesTwo, Collections.<AppliedPTransform<?, ?, ?>>emptyList());
    consumers.put(keyed, Collections.<AppliedPTransform<?, ?, ?>>emptyList());

    consumers.put(
        intsToFlatten,
        Collections.<AppliedPTransform<?, ?, ?>>singleton(
            flattened.getProducingTransformInternal()));
    consumers.put(flattened, Collections.<AppliedPTransform<?, ?, ?>>emptyList());

    clock = MockClock.fromInstant(new Instant(1000));

    manager = InMemoryWatermarkManager.create(clock, rootTransforms, consumers);
  }

  /**
   * Demonstrates that getWatermark, when called on an {@link AppliedPTransform} that has not
   * processed any elements, returns the {@link BoundedWindow#TIMESTAMP_MIN_VALUE}.
   */
  @Test
  public void getWatermarkForUntouchedTransform() {
    TransformWatermarks watermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());

    assertThat(watermarks.getInputWatermark(), equalTo(BoundedWindow.TIMESTAMP_MIN_VALUE));
    assertThat(watermarks.getOutputWatermark(), equalTo(BoundedWindow.TIMESTAMP_MIN_VALUE));
  }

  /**
   * Demonstrates that getWatermark for a transform that consumes no input uses the Watermark
   * Hold value provided to it as the output watermark.
   */
  @Test
  public void getWatermarkForUpdatedSourceTransform() {
    CommittedBundle<Integer> output = globallyWindowedBundle(createdInts, 1);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(output), new Instant(8000L));
    TransformWatermarks updatedSourceWatermark =
        manager.getWatermarks(createdInts.getProducingTransformInternal());

    assertThat(updatedSourceWatermark.getOutputWatermark(), equalTo(new Instant(8000L)));
  }

  /**
   * Demonstrates that getWatermark for a transform that takes multiple inputs is held to the
   * minimum watermark across all of its inputs.
   */
  @Test
  public void getWatermarkForMultiInputTransform() {
    CommittedBundle<Integer> secondPcollectionBundle = globallyWindowedBundle(intsToFlatten, -1);

    manager.updateWatermarks(null, intsToFlatten.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(secondPcollectionBundle),
        BoundedWindow.TIMESTAMP_MAX_VALUE);

    // We didn't do anything for the first source, so we shouldn't have progressed the watermark
    TransformWatermarks firstSourceWatermark =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(
        firstSourceWatermark.getOutputWatermark(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));

    // the Second Source output all of the elements so it should be done (with a watermark at the
    // end of time).
    TransformWatermarks secondSourceWatermark =
        manager.getWatermarks(intsToFlatten.getProducingTransformInternal());
    assertThat(
        secondSourceWatermark.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));

    // We haven't consumed anything yet, so our watermark should be at the beginning of time
    TransformWatermarks transformWatermark =
        manager.getWatermarks(flattened.getProducingTransformInternal());
    assertThat(
        transformWatermark.getInputWatermark(), not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));
    assertThat(
        transformWatermark.getOutputWatermark(), not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));

    CommittedBundle<Integer> flattenedBundleSecondCreate = globallyWindowedBundle(flattened, -1);
    // We have finished processing the bundle from the second PCollection, but we haven't consumed
    // anything from the first PCollection yet; so our watermark shouldn't advance
    manager.updateWatermarks(secondPcollectionBundle, flattened.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(flattenedBundleSecondCreate),
        null);
    TransformWatermarks transformAfterProcessing =
        manager.getWatermarks(flattened.getProducingTransformInternal());
    manager.updateWatermarks(secondPcollectionBundle, flattened.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(flattenedBundleSecondCreate),
        null);
    assertThat(
        transformAfterProcessing.getInputWatermark(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));
    assertThat(
        transformAfterProcessing.getOutputWatermark(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));

    Instant firstCollectionTimestamp = new Instant(10000);
    CommittedBundle<Integer> firstPcollectionBundle =
        timestampedBundle(createdInts, TimestampedValue.<Integer>of(5, firstCollectionTimestamp));
    // the source is done, but elements are still buffered. The source output watermark should be
    // past the end of the global window
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(firstPcollectionBundle),
        new Instant(Long.MAX_VALUE));
    TransformWatermarks firstSourceWatermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(
        firstSourceWatermarks.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));

    // We still haven't consumed any of the first source's input, so the watermark should still not
    // progress
    TransformWatermarks flattenAfterSourcesProduced =
        manager.getWatermarks(flattened.getProducingTransformInternal());
    assertThat(
        flattenAfterSourcesProduced.getInputWatermark(), not(laterThan(firstCollectionTimestamp)));
    assertThat(
        flattenAfterSourcesProduced.getOutputWatermark(), not(laterThan(firstCollectionTimestamp)));

    // We have buffered inputs, but since the PCollection has all of the elements (has a WM past the
    // end of the global window), we should have a watermark equal to the min among buffered
    // elements
    TransformWatermarks withBufferedElements =
        manager.getWatermarks(flattened.getProducingTransformInternal());
    assertThat(withBufferedElements.getInputWatermark(), equalTo(firstCollectionTimestamp));
    assertThat(withBufferedElements.getOutputWatermark(), equalTo(firstCollectionTimestamp));

    CommittedBundle<?> completedFlattenBundle =
        InProcessBundle.unkeyed(flattened).commit(BoundedWindow.TIMESTAMP_MAX_VALUE);
    manager.updateWatermarks(firstPcollectionBundle, flattened.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(completedFlattenBundle),
        null);
    TransformWatermarks afterConsumingAllInput =
        manager.getWatermarks(flattened.getProducingTransformInternal());
    assertThat(
        afterConsumingAllInput.getInputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(
        afterConsumingAllInput.getOutputWatermark(),
        not(laterThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
  }

  /**
   * Demonstrates that pending elements are independent among
   * {@link AppliedPTransform AppliedPTransforms} that consume the same input {@link PCollection}.
   */
  @Test
  public void getWatermarkForMultiConsumedCollection() {
    CommittedBundle<Integer> createdBundle = timestampedBundle(createdInts,
        TimestampedValue.of(1, new Instant(1_000_000L)), TimestampedValue.of(2, new Instant(1234L)),
        TimestampedValue.of(3, new Instant(-1000L)));
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createdBundle), new Instant(Long.MAX_VALUE));
    TransformWatermarks createdAfterProducing =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(
        createdAfterProducing.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));

    CommittedBundle<KV<String, Integer>> keyBundle =
        timestampedBundle(keyed, TimestampedValue.of(KV.of("MyKey", 1), new Instant(1_000_000L)),
            TimestampedValue.of(KV.of("MyKey", 2), new Instant(1234L)),
            TimestampedValue.of(KV.of("MyKey", 3), new Instant(-1000L)));
    manager.updateWatermarks(createdBundle, keyed.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(keyBundle), null);
    TransformWatermarks keyedWatermarks =
        manager.getWatermarks(keyed.getProducingTransformInternal());
    assertThat(
        keyedWatermarks.getInputWatermark(), not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(
        keyedWatermarks.getOutputWatermark(), not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));

    TransformWatermarks filteredWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(filteredWatermarks.getInputWatermark(), not(laterThan(new Instant(-1000L))));
    assertThat(filteredWatermarks.getOutputWatermark(), not(laterThan(new Instant(-1000L))));

    CommittedBundle<Integer> filteredBundle =
        timestampedBundle(filtered, TimestampedValue.of(2, new Instant(1234L)));
    manager.updateWatermarks(createdBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(filteredBundle), null);
    TransformWatermarks filteredProcessedWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(
        filteredProcessedWatermarks.getInputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(
        filteredProcessedWatermarks.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
  }

  /**
   * Demonstrates that the watermark of an {@link AppliedPTransform} is held to the provided
   * watermark hold.
   */
  @Test
  public void updateWatermarkWithWatermarkHolds() {
    CommittedBundle<Integer> createdBundle = timestampedBundle(createdInts,
        TimestampedValue.of(1, new Instant(1_000_000L)), TimestampedValue.of(2, new Instant(1234L)),
        TimestampedValue.of(3, new Instant(-1000L)));
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createdBundle), new Instant(Long.MAX_VALUE));

    CommittedBundle<KV<String, Integer>> keyBundle =
        timestampedBundle(keyed, TimestampedValue.of(KV.of("MyKey", 1), new Instant(1_000_000L)),
            TimestampedValue.of(KV.of("MyKey", 2), new Instant(1234L)),
            TimestampedValue.of(KV.of("MyKey", 3), new Instant(-1000L)));
    manager.updateWatermarks(createdBundle, keyed.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(keyBundle),
        new Instant(500L));
    TransformWatermarks keyedWatermarks =
        manager.getWatermarks(keyed.getProducingTransformInternal());
    assertThat(
        keyedWatermarks.getInputWatermark(), not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(keyedWatermarks.getOutputWatermark(), not(laterThan(new Instant(500L))));
  }

  /**
   * Demonstrates that the watermark of an {@link AppliedPTransform} is held to the provided
   * watermark hold.
   */
  @Test
  public void updateWatermarkWithKeyedWatermarkHolds() {
    CommittedBundle<Integer> firstKeyBundle =
        InProcessBundle.keyed(createdInts, "Odd")
            .add(WindowedValue.timestampedValueInGlobalWindow(1, new Instant(1_000_000L)))
            .add(WindowedValue.timestampedValueInGlobalWindow(3, new Instant(-1000L)))
            .commit(clock.now());

    CommittedBundle<Integer> secondKeyBundle =
        InProcessBundle.keyed(createdInts, "Even")
            .add(WindowedValue.timestampedValueInGlobalWindow(2, new Instant(1234L)))
            .commit(clock.now());

    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        ImmutableList.of(firstKeyBundle, secondKeyBundle), BoundedWindow.TIMESTAMP_MAX_VALUE);

    manager.updateWatermarks(firstKeyBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>emptyList(), new Instant(-1000L));
    manager.updateWatermarks(secondKeyBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>emptyList(), new Instant(1234L));

    TransformWatermarks filteredWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(
        filteredWatermarks.getInputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(filteredWatermarks.getOutputWatermark(), not(laterThan(new Instant(-1000L))));

    CommittedBundle<Integer> fauxFirstKeyTimerBundle =
        InProcessBundle.keyed(createdInts, "Odd").commit(clock.now());
    manager.updateWatermarks(fauxFirstKeyTimerBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>emptyList(),
        BoundedWindow.TIMESTAMP_MAX_VALUE);

    assertThat(filteredWatermarks.getOutputWatermark(), equalTo(new Instant(1234L)));

    CommittedBundle<Integer> fauxSecondKeyTimerBundle =
        InProcessBundle.keyed(createdInts, "Even").commit(clock.now());
    manager.updateWatermarks(fauxSecondKeyTimerBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>emptyList(), new Instant(5678L));
    assertThat(filteredWatermarks.getOutputWatermark(), equalTo(new Instant(5678L)));

    manager.updateWatermarks(fauxSecondKeyTimerBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>emptyList(),
        BoundedWindow.TIMESTAMP_MAX_VALUE);
    assertThat(
        filteredWatermarks.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
  }

  /**
   * Demonstrates that updated output watermarks are monotonic in the presence of late data, when
   * called on an {@link AppliedPTransform} that consumes no input.
   */
  @Test
  public void updateOutputWatermarkShouldBeMonotonic() {
    CommittedBundle<?> firstInput =
        InProcessBundle.unkeyed(createdInts).commit(BoundedWindow.TIMESTAMP_MAX_VALUE);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(firstInput), new Instant(0L));
    TransformWatermarks firstWatermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(firstWatermarks.getOutputWatermark(), equalTo(new Instant(0L)));

    CommittedBundle<?> secondInput =
        InProcessBundle.unkeyed(createdInts).commit(BoundedWindow.TIMESTAMP_MAX_VALUE);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(secondInput), new Instant(-250L));
    TransformWatermarks secondWatermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(secondWatermarks.getOutputWatermark(), not(earlierThan(new Instant(0L))));
  }

  /**
   * Demonstrates that updated output watermarks are monotonic in the presence of watermark holds
   * that become earlier than a previous watermark hold.
   */
  @Test
  public void updateWatermarkWithHoldsShouldBeMonotonic() {
    CommittedBundle<Integer> createdBundle = timestampedBundle(createdInts,
        TimestampedValue.of(1, new Instant(1_000_000L)), TimestampedValue.of(2, new Instant(1234L)),
        TimestampedValue.of(3, new Instant(-1000L)));
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createdBundle), new Instant(Long.MAX_VALUE));

    CommittedBundle<KV<String, Integer>> keyBundle =
        timestampedBundle(keyed, TimestampedValue.of(KV.of("MyKey", 1), new Instant(1_000_000L)),
            TimestampedValue.of(KV.of("MyKey", 2), new Instant(1234L)),
            TimestampedValue.of(KV.of("MyKey", 3), new Instant(-1000L)));
    manager.updateWatermarks(createdBundle, keyed.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(keyBundle),
        new Instant(500L));
    TransformWatermarks keyedWatermarks =
        manager.getWatermarks(keyed.getProducingTransformInternal());
    assertThat(
        keyedWatermarks.getInputWatermark(), not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(keyedWatermarks.getOutputWatermark(), not(laterThan(new Instant(500L))));
    Instant oldOutputWatermark = keyedWatermarks.getOutputWatermark();

    TransformWatermarks updatedWatermarks =
        manager.getWatermarks(keyed.getProducingTransformInternal());
    assertThat(
        updatedWatermarks.getInputWatermark(), not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    // We added a hold prior to the old watermark; we shouldn't progress (due to the earlier hold)
    // but the watermark is monotonic and should not backslide to the new, earlier hold
    assertThat(updatedWatermarks.getOutputWatermark(), equalTo(oldOutputWatermark));
  }

  /**
   * Demonstrates that updateWatermarks in the presence of late data is monotonic.
   */
  @Test
  public void updateWatermarkWithLateData() {
    Instant sourceWatermark = new Instant(1_000_000L);
    CommittedBundle<Integer> createdBundle = timestampedBundle(createdInts,
        TimestampedValue.of(1, sourceWatermark), TimestampedValue.of(2, new Instant(1234L)));

    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createdBundle), sourceWatermark);

    CommittedBundle<KV<String, Integer>> keyBundle =
        timestampedBundle(keyed, TimestampedValue.of(KV.of("MyKey", 1), sourceWatermark),
            TimestampedValue.of(KV.of("MyKey", 2), new Instant(1234L)));

    // Finish processing the on-time data. The watermarks should progress to be equal to the source
    manager.updateWatermarks(createdBundle, keyed.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(keyBundle), null);
    TransformWatermarks onTimeWatermarks =
        manager.getWatermarks(keyed.getProducingTransformInternal());
    assertThat(onTimeWatermarks.getInputWatermark(), equalTo(sourceWatermark));
    assertThat(onTimeWatermarks.getOutputWatermark(), equalTo(sourceWatermark));

    CommittedBundle<Integer> lateDataBundle =
        timestampedBundle(createdInts, TimestampedValue.of(3, new Instant(-1000L)));
    // the late data arrives in a downstream PCollection after its watermark has advanced past it;
    // we don't advance the watermark past the current watermark until we've consumed the late data
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(lateDataBundle), new Instant(2_000_000L));
    TransformWatermarks bufferedLateWm =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(bufferedLateWm.getOutputWatermark(), equalTo(new Instant(2_000_000L)));

    // The input watermark should be held to its previous value (not advanced due to late data; not
    // moved backwards in the presence of watermarks due to monotonicity).
    TransformWatermarks lateDataBufferedWatermark =
        manager.getWatermarks(keyed.getProducingTransformInternal());
    assertThat(lateDataBufferedWatermark.getInputWatermark(), not(earlierThan(sourceWatermark)));
    assertThat(lateDataBufferedWatermark.getOutputWatermark(), not(earlierThan(sourceWatermark)));

    CommittedBundle<KV<String, Integer>> lateKeyedBundle =
        timestampedBundle(keyed, TimestampedValue.of(KV.of("MyKey", 3), new Instant(-1000L)));
    manager.updateWatermarks(lateDataBundle, keyed.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(lateKeyedBundle), null);
  }

  /**
   * Demonstrates that after watermarks of an upstream transform are updated, but no output has been
   * produced, the watermarks of a downstream process are advanced.
   */
  @Test
  public void getWatermarksAfterOnlyEmptyOutput() {
    CommittedBundle<Integer> emptyCreateOutput = globallyWindowedBundle(createdInts);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(emptyCreateOutput),
        BoundedWindow.TIMESTAMP_MAX_VALUE);
    TransformWatermarks updatedSourceWatermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());

    assertThat(
        updatedSourceWatermarks.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));

    TransformWatermarks finishedFilterWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(
        finishedFilterWatermarks.getInputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(
        finishedFilterWatermarks.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
  }

  /**
   * Demonstrates that after watermarks of an upstream transform are updated, but no output has been
   * produced, and the downstream transform has a watermark hold, the watermark is held to the hold.
   */
  @Test
  public void getWatermarksAfterHoldAndEmptyOutput() {
    CommittedBundle<Integer> firstCreateOutput = globallyWindowedBundle(createdInts, 1, 2);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(firstCreateOutput), new Instant(12_000L));

    CommittedBundle<Integer> firstFilterOutput = globallyWindowedBundle(filtered);
    manager.updateWatermarks(firstCreateOutput, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(firstFilterOutput),
        new Instant(10_000L));
    TransformWatermarks firstFilterWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(firstFilterWatermarks.getInputWatermark(), not(earlierThan(new Instant(12_000L))));
    assertThat(firstFilterWatermarks.getOutputWatermark(), not(laterThan(new Instant(10_000L))));

    CommittedBundle<Integer> emptyCreateOutput = globallyWindowedBundle(createdInts);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(emptyCreateOutput),
        BoundedWindow.TIMESTAMP_MAX_VALUE);
    TransformWatermarks updatedSourceWatermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());

    assertThat(
        updatedSourceWatermarks.getOutputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));

    TransformWatermarks finishedFilterWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(
        finishedFilterWatermarks.getInputWatermark(),
        not(earlierThan(BoundedWindow.TIMESTAMP_MAX_VALUE)));
    assertThat(finishedFilterWatermarks.getOutputWatermark(), not(laterThan(new Instant(10_000L))));
  }

  @Test
  public void getSynchronizedProcessingTimeInputWatermarksHeldToPendingBundles() {
    TransformWatermarks watermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(watermarks.getSynchronizedProcessingInputTime(), equalTo(clock.now()));
    assertThat(
        watermarks.getSynchronizedProcessingOutputTime(),
        equalTo(BoundedWindow.TIMESTAMP_MIN_VALUE));

    TransformWatermarks filteredWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    // Non-root processing watermarks don't progress until data has been processed
    assertThat(
        filteredWatermarks.getSynchronizedProcessingInputTime(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));
    assertThat(
        filteredWatermarks.getSynchronizedProcessingOutputTime(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));

    CommittedBundle<Integer> createOutput =
        InProcessBundle.unkeyed(createdInts).commit(new Instant(1250L));

    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createOutput), BoundedWindow.TIMESTAMP_MAX_VALUE);
    TransformWatermarks createAfterUpdate =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(createAfterUpdate.getSynchronizedProcessingInputTime(), equalTo(clock.now()));
    assertThat(createAfterUpdate.getSynchronizedProcessingOutputTime(), equalTo(clock.now()));

    TransformWatermarks filterAfterProduced =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(
        filterAfterProduced.getSynchronizedProcessingInputTime(), not(laterThan(clock.now())));
    assertThat(
        filterAfterProduced.getSynchronizedProcessingOutputTime(), not(laterThan(clock.now())));

    clock.set(new Instant(1500L));
    assertThat(createAfterUpdate.getSynchronizedProcessingInputTime(), equalTo(clock.now()));
    assertThat(createAfterUpdate.getSynchronizedProcessingOutputTime(), equalTo(clock.now()));
    assertThat(
        filterAfterProduced.getSynchronizedProcessingInputTime(),
        not(laterThan(new Instant(1250L))));
    assertThat(
        filterAfterProduced.getSynchronizedProcessingOutputTime(),
        not(laterThan(new Instant(1250L))));

    CommittedBundle<?> filterOutputBundle =
        InProcessBundle.unkeyed(intsToFlatten).commit(new Instant(1250L));
    manager.updateWatermarks(createOutput, filtered.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>singleton(filterOutputBundle),
        BoundedWindow.TIMESTAMP_MAX_VALUE);
    TransformWatermarks filterAfterConsumed =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    assertThat(
        filterAfterConsumed.getSynchronizedProcessingInputTime(),
        not(laterThan(createAfterUpdate.getSynchronizedProcessingOutputTime())));
    assertThat(
        filterAfterConsumed.getSynchronizedProcessingOutputTime(),
        not(laterThan(filterAfterConsumed.getSynchronizedProcessingInputTime())));
  }

  /**
   * Demonstrates that the Synchronized Processing Time output watermark cannot progress past
   * pending timers in the same set. This propagates to all downstream SynchronizedProcessingTimes.
   *
   * <p>Also demonstrate that the result is monotonic.
   */
  //  @Test
  public void getSynchronizedProcessingTimeOutputHeldToPendingTimers() {
    CommittedBundle<Integer> createdBundle = globallyWindowedBundle(createdInts, 1, 2, 4, 8);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createdBundle), new Instant(1248L));

    TransformWatermarks filteredWms =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    TransformWatermarks filteredDoubledWms =
        manager.getWatermarks(filteredTimesTwo.getProducingTransformInternal());
    Instant initialFilteredWm = filteredWms.getSynchronizedProcessingOutputTime();
    Instant initialFilteredDoubledWm = filteredDoubledWms.getSynchronizedProcessingOutputTime();

    CommittedBundle<Integer> filteredBundle = globallyWindowedBundle(filtered, 2, 8);
    TimerData pastTimer =
        TimerData.of(StateNamespaces.global(), new Instant(250L), TimeDomain.PROCESSING_TIME);
    TimerData futureTimer =
        TimerData.of(StateNamespaces.global(), new Instant(4096L), TimeDomain.PROCESSING_TIME);
    TimerUpdate timers =
        TimerUpdate.builder("key").setTimer(pastTimer).setTimer(futureTimer).build();
    manager.updateWatermarks(createdBundle, filtered.getProducingTransformInternal(), timers,
        Collections.<CommittedBundle<?>>singleton(filteredBundle),
        BoundedWindow.TIMESTAMP_MAX_VALUE);
    Instant startTime = clock.now();
    clock.set(startTime.plus(250L));
    // We're held based on the past timer
    assertThat(filteredWms.getSynchronizedProcessingOutputTime(), not(laterThan(startTime)));
    assertThat(filteredDoubledWms.getSynchronizedProcessingOutputTime(), not(laterThan(startTime)));
    // And we're monotonic
    assertThat(
        filteredWms.getSynchronizedProcessingOutputTime(), not(earlierThan(initialFilteredWm)));
    assertThat(
        filteredDoubledWms.getSynchronizedProcessingOutputTime(),
        not(earlierThan(initialFilteredDoubledWm)));

    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> firedTimers =
        manager.extractFiredTimers();
    assertThat(
        firedTimers.get(filtered.getProducingTransformInternal())
            .get("key")
            .getTimers(TimeDomain.PROCESSING_TIME),
        contains(pastTimer));
    // Our timer has fired, but has not been completed, so it holds our synchronized processing WM
    assertThat(filteredWms.getSynchronizedProcessingOutputTime(), not(laterThan(startTime)));
    assertThat(filteredDoubledWms.getSynchronizedProcessingOutputTime(), not(laterThan(startTime)));

    CommittedBundle<Integer> filteredTimerBundle =
        InProcessBundle.keyed(filtered, "key").commit(BoundedWindow.TIMESTAMP_MAX_VALUE);
    CommittedBundle<Integer> filteredTimerResult =
        InProcessBundle.keyed(filteredTimesTwo, "key")
            .commit(filteredWms.getSynchronizedProcessingOutputTime());
    // Complete the processing time timer
    manager.updateWatermarks(filteredTimerBundle, filtered.getProducingTransformInternal(),
        TimerUpdate.builder("key")
            .withCompletedTimers(Collections.<TimerData>singleton(pastTimer))
            .build(),
        Collections.<CommittedBundle<?>>singleton(filteredTimerResult),
        BoundedWindow.TIMESTAMP_MAX_VALUE);

    clock.set(startTime.plus(500L));
    assertThat(filteredWms.getSynchronizedProcessingOutputTime(), not(laterThan(clock.now())));
    // filtered should be held to the time at which the filteredTimerResult fired
    assertThat(
        filteredDoubledWms.getSynchronizedProcessingOutputTime(),
        not(earlierThan(filteredTimerResult.getSynchronizedProcessingOutputWatermark())));

    manager.updateWatermarks(filteredTimerResult, filteredTimesTwo.getProducingTransformInternal(),
        TimerUpdate.empty(), Collections.<CommittedBundle<?>>emptyList(),
        BoundedWindow.TIMESTAMP_MAX_VALUE);
    assertThat(filteredDoubledWms.getSynchronizedProcessingOutputTime(), equalTo(clock.now()));

    clock.set(new Instant(Long.MAX_VALUE));
    assertThat(filteredWms.getSynchronizedProcessingOutputTime(), equalTo(new Instant(4096)));
    assertThat(
        filteredDoubledWms.getSynchronizedProcessingOutputTime(), equalTo(new Instant(4096)));
  }

  /**
   * Demonstrates that if any earlier processing holds appear in the synchronized processing time
   * output hold the result is monotonic.
   */
  @Test
  public void getSynchronizedProcessingTimeOutputTimeIsMonotonic() {
    Instant startTime = clock.now();
    TransformWatermarks watermarks =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(watermarks.getSynchronizedProcessingInputTime(), equalTo(startTime));

    TransformWatermarks filteredWatermarks =
        manager.getWatermarks(filtered.getProducingTransformInternal());
    // Non-root processing watermarks don't progress until data has been processed
    assertThat(
        filteredWatermarks.getSynchronizedProcessingInputTime(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));
    assertThat(
        filteredWatermarks.getSynchronizedProcessingOutputTime(),
        not(laterThan(BoundedWindow.TIMESTAMP_MIN_VALUE)));

    CommittedBundle<Integer> createOutput =
        InProcessBundle.unkeyed(createdInts).commit(new Instant(1250L));

    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createOutput), BoundedWindow.TIMESTAMP_MAX_VALUE);
    TransformWatermarks createAfterUpdate =
        manager.getWatermarks(createdInts.getProducingTransformInternal());
    assertThat(createAfterUpdate.getSynchronizedProcessingInputTime(), not(laterThan(clock.now())));
    assertThat(
        createAfterUpdate.getSynchronizedProcessingOutputTime(), not(laterThan(clock.now())));

    CommittedBundle<Integer> createSecondOutput =
        InProcessBundle.unkeyed(createdInts).commit(new Instant(750L));
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(createSecondOutput),
        BoundedWindow.TIMESTAMP_MAX_VALUE);

    assertThat(createAfterUpdate.getSynchronizedProcessingOutputTime(), equalTo(clock.now()));
  }

  @Test
  public void synchronizedProcessingInputTimeIsHeldToUpstreamProcessingTimeTimers() {
    CommittedBundle<Integer> created = globallyWindowedBundle(createdInts, 1, 2, 3);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(created), new Instant(40_900L));

    CommittedBundle<Integer> filteredBundle = globallyWindowedBundle(filtered, 2, 4);
    Instant upstreamHold = new Instant(2048L);
    TimerData upstreamProcessingTimer =
        TimerData.of(StateNamespaces.global(), upstreamHold, TimeDomain.PROCESSING_TIME);
    manager.updateWatermarks(created, filtered.getProducingTransformInternal(),
        TimerUpdate.builder("key").setTimer(upstreamProcessingTimer).build(),
        Collections.<CommittedBundle<?>>singleton(filteredBundle),
        BoundedWindow.TIMESTAMP_MAX_VALUE);

    TransformWatermarks downstreamWms =
        manager.getWatermarks(filteredTimesTwo.getProducingTransformInternal());
    assertThat(downstreamWms.getSynchronizedProcessingInputTime(), equalTo(clock.now()));

    clock.set(BoundedWindow.TIMESTAMP_MAX_VALUE);
    assertThat(downstreamWms.getSynchronizedProcessingInputTime(), equalTo(upstreamHold));

    manager.extractFiredTimers();
    // Pending processing time timers that have been fired but aren't completed hold the
    // synchronized processing time
    assertThat(downstreamWms.getSynchronizedProcessingInputTime(), equalTo(upstreamHold));

    CommittedBundle<Integer> otherCreated = globallyWindowedBundle(createdInts, 4, 8, 12);
    manager.updateWatermarks(otherCreated, filtered.getProducingTransformInternal(),
        TimerUpdate.builder("key")
            .withCompletedTimers(Collections.singleton(upstreamProcessingTimer))
            .build(),
        Collections.<CommittedBundle<?>>emptyList(), BoundedWindow.TIMESTAMP_MAX_VALUE);

    assertThat(downstreamWms.getSynchronizedProcessingInputTime(), not(earlierThan(clock.now())));
  }

  @Test
  public void synchronizedProcessingInputTimeIsHeldToPendingBundleTimes() {
    CommittedBundle<Integer> created = globallyWindowedBundle(createdInts, 1, 2, 3);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(created), new Instant(29_919_235L));

    Instant upstreamHold = new Instant(2048L);
    CommittedBundle<Integer> filteredBundle =
        InProcessBundle.keyed(filtered, "key").commit(upstreamHold);
    manager.updateWatermarks(created, filtered.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>singleton(filteredBundle),
        BoundedWindow.TIMESTAMP_MAX_VALUE);

    TransformWatermarks downstreamWms =
        manager.getWatermarks(filteredTimesTwo.getProducingTransformInternal());
    assertThat(downstreamWms.getSynchronizedProcessingInputTime(), equalTo(clock.now()));

    clock.set(BoundedWindow.TIMESTAMP_MAX_VALUE);
    assertThat(downstreamWms.getSynchronizedProcessingInputTime(), equalTo(upstreamHold));
  }

  @Test
  public void extractFiredTimersReturnsFiredEventTimeTimers() {
    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> initialTimers =
        manager.extractFiredTimers();
    // Watermarks haven't advanced
    assertThat(initialTimers.entrySet(), emptyIterable());

    // Advance WM of keyed past the first timer, but ahead of the second and third
    CommittedBundle<Integer> createdBundle = globallyWindowedBundle(filtered);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.singleton(createdBundle), new Instant(1500L));

    TimerData earliestTimer =
        TimerData.of(StateNamespaces.global(), new Instant(1000), TimeDomain.EVENT_TIME);
    TimerData middleTimer =
        TimerData.of(StateNamespaces.global(), new Instant(5000L), TimeDomain.EVENT_TIME);
    TimerData lastTimer =
        TimerData.of(StateNamespaces.global(), new Instant(10000L), TimeDomain.EVENT_TIME);
    Object key = new Object();
    TimerUpdate update =
        TimerUpdate.builder(key)
            .setTimer(earliestTimer)
            .setTimer(middleTimer)
            .setTimer(lastTimer)
            .build();

    manager.updateWatermarks(createdBundle, filtered.getProducingTransformInternal(), update,
        Collections.<CommittedBundle<?>>singleton(globallyWindowedBundle(intsToFlatten)),
        new Instant(1000L));

    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> firstTransformFiredTimers =
        manager.extractFiredTimers();
    assertThat(
        firstTransformFiredTimers.get(filtered.getProducingTransformInternal()), not(nullValue()));
    Map<Object, FiredTimers> firstFilteredTimers =
        firstTransformFiredTimers.get(filtered.getProducingTransformInternal());
    assertThat(firstFilteredTimers.get(key), not(nullValue()));
    FiredTimers firstFired = firstFilteredTimers.get(key);
    assertThat(firstFired.getTimers(TimeDomain.EVENT_TIME), contains(earliestTimer));

    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>emptyList(), new Instant(50_000L));
    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> secondTransformFiredTimers =
        manager.extractFiredTimers();
    assertThat(
        secondTransformFiredTimers.get(filtered.getProducingTransformInternal()), not(nullValue()));
    Map<Object, FiredTimers> secondFilteredTimers =
        secondTransformFiredTimers.get(filtered.getProducingTransformInternal());
    assertThat(secondFilteredTimers.get(key), not(nullValue()));
    FiredTimers secondFired = secondFilteredTimers.get(key);
    // Contains, in order, middleTimer and then lastTimer
    assertThat(secondFired.getTimers(TimeDomain.EVENT_TIME), contains(middleTimer, lastTimer));
  }

  @Test
  public void extractFiredTimersReturnsFiredProcessingTimeTimers() {
    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> initialTimers =
        manager.extractFiredTimers();
    // Watermarks haven't advanced
    assertThat(initialTimers.entrySet(), emptyIterable());

    // Advance WM of keyed past the first timer, but ahead of the second and third
    CommittedBundle<Integer> createdBundle = globallyWindowedBundle(filtered);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.singleton(createdBundle), new Instant(1500L));

    TimerData earliestTimer =
        TimerData.of(StateNamespaces.global(), new Instant(999L), TimeDomain.PROCESSING_TIME);
    TimerData middleTimer =
        TimerData.of(StateNamespaces.global(), new Instant(5000L), TimeDomain.PROCESSING_TIME);
    TimerData lastTimer =
        TimerData.of(StateNamespaces.global(), new Instant(10000L), TimeDomain.PROCESSING_TIME);
    Object key = new Object();
    TimerUpdate update =
        TimerUpdate.builder(key)
            .setTimer(lastTimer)
            .setTimer(earliestTimer)
            .setTimer(middleTimer)
            .build();

    manager.updateWatermarks(createdBundle, filtered.getProducingTransformInternal(), update,
        Collections.<CommittedBundle<?>>singleton(globallyWindowedBundle(intsToFlatten)),
        new Instant(1000L));

    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> firstTransformFiredTimers =
        manager.extractFiredTimers();
    assertThat(
        firstTransformFiredTimers.get(filtered.getProducingTransformInternal()), not(nullValue()));
    Map<Object, FiredTimers> firstFilteredTimers =
        firstTransformFiredTimers.get(filtered.getProducingTransformInternal());
    assertThat(firstFilteredTimers.get(key), not(nullValue()));
    FiredTimers firstFired = firstFilteredTimers.get(key);
    assertThat(firstFired.getTimers(TimeDomain.PROCESSING_TIME), contains(earliestTimer));

    clock.set(new Instant(50_000L));
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>emptyList(), new Instant(50_000L));
    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> secondTransformFiredTimers =
        manager.extractFiredTimers();
    assertThat(
        secondTransformFiredTimers.get(filtered.getProducingTransformInternal()), not(nullValue()));
    Map<Object, FiredTimers> secondFilteredTimers =
        secondTransformFiredTimers.get(filtered.getProducingTransformInternal());
    assertThat(secondFilteredTimers.get(key), not(nullValue()));
    FiredTimers secondFired = secondFilteredTimers.get(key);
    // Contains, in order, middleTimer and then lastTimer
    assertThat(secondFired.getTimers(TimeDomain.PROCESSING_TIME), contains(middleTimer, lastTimer));
  }

  @Test
  public void extractFiredTimersReturnsFiredSynchronizedProcessingTimeTimers() {
    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> initialTimers =
        manager.extractFiredTimers();
    // Watermarks haven't advanced
    assertThat(initialTimers.entrySet(), emptyIterable());

    // Advance WM of keyed past the first timer, but ahead of the second and third
    CommittedBundle<Integer> createdBundle = globallyWindowedBundle(filtered);
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.singleton(createdBundle), new Instant(1500L));

    TimerData earliestTimer = TimerData.of(
        StateNamespaces.global(), new Instant(999L), TimeDomain.SYNCHRONIZED_PROCESSING_TIME);
    TimerData middleTimer = TimerData.of(
        StateNamespaces.global(), new Instant(5000L), TimeDomain.SYNCHRONIZED_PROCESSING_TIME);
    TimerData lastTimer = TimerData.of(
        StateNamespaces.global(), new Instant(10000L), TimeDomain.SYNCHRONIZED_PROCESSING_TIME);
    Object key = new Object();
    TimerUpdate update =
        TimerUpdate.builder(key)
            .setTimer(lastTimer)
            .setTimer(earliestTimer)
            .setTimer(middleTimer)
            .build();

    manager.updateWatermarks(createdBundle, filtered.getProducingTransformInternal(), update,
        Collections.<CommittedBundle<?>>singleton(globallyWindowedBundle(intsToFlatten)),
        new Instant(1000L));

    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> firstTransformFiredTimers =
        manager.extractFiredTimers();
    assertThat(
        firstTransformFiredTimers.get(filtered.getProducingTransformInternal()), not(nullValue()));
    Map<Object, FiredTimers> firstFilteredTimers =
        firstTransformFiredTimers.get(filtered.getProducingTransformInternal());
    assertThat(firstFilteredTimers.get(key), not(nullValue()));
    FiredTimers firstFired = firstFilteredTimers.get(key);
    assertThat(
        firstFired.getTimers(TimeDomain.SYNCHRONIZED_PROCESSING_TIME), contains(earliestTimer));

    clock.set(new Instant(50_000L));
    manager.updateWatermarks(null, createdInts.getProducingTransformInternal(), TimerUpdate.empty(),
        Collections.<CommittedBundle<?>>emptyList(), new Instant(50_000L));
    Map<AppliedPTransform<?, ?, ?>, Map<Object, FiredTimers>> secondTransformFiredTimers =
        manager.extractFiredTimers();
    assertThat(
        secondTransformFiredTimers.get(filtered.getProducingTransformInternal()), not(nullValue()));
    Map<Object, FiredTimers> secondFilteredTimers =
        secondTransformFiredTimers.get(filtered.getProducingTransformInternal());
    assertThat(secondFilteredTimers.get(key), not(nullValue()));
    FiredTimers secondFired = secondFilteredTimers.get(key);
    // Contains, in order, middleTimer and then lastTimer
    assertThat(
        secondFired.getTimers(TimeDomain.SYNCHRONIZED_PROCESSING_TIME),
        contains(middleTimer, lastTimer));
  }

  @Test
  public void timerUpdateBuilderBuildAddsAllAddedTimers() {
    TimerData set = TimerData.of(StateNamespaces.global(), new Instant(10L), TimeDomain.EVENT_TIME);
    TimerData deleted =
        TimerData.of(StateNamespaces.global(), new Instant(24L), TimeDomain.PROCESSING_TIME);
    TimerData completedOne = TimerData.of(
        StateNamespaces.global(), new Instant(1024L), TimeDomain.SYNCHRONIZED_PROCESSING_TIME);
    TimerData completedTwo =
        TimerData.of(StateNamespaces.global(), new Instant(2048L), TimeDomain.EVENT_TIME);

    TimerUpdate update =
        TimerUpdate.builder("foo")
            .withCompletedTimers(ImmutableList.of(completedOne, completedTwo))
            .setTimer(set)
            .deletedTimer(deleted)
            .build();

    assertThat(update.getCompletedTimers(), containsInAnyOrder(completedOne, completedTwo));
    assertThat(update.getSetTimers(), contains(set));
    assertThat(update.getDeletedTimers(), contains(deleted));
  }

  @Test
  public void timerUpdateBuilderWithSetThenDeleteHasOnlyDeleted() {
    TimerUpdateBuilder builder = TimerUpdate.builder(null);
    TimerData timer = TimerData.of(StateNamespaces.global(), Instant.now(), TimeDomain.EVENT_TIME);

    TimerUpdate built = builder.setTimer(timer).deletedTimer(timer).build();

    assertThat(built.getSetTimers(), emptyIterable());
    assertThat(built.getDeletedTimers(), contains(timer));
  }

  @Test
  public void timerUpdateBuilderWithDeleteThenSetHasOnlySet() {
    TimerUpdateBuilder builder = TimerUpdate.builder(null);
    TimerData timer = TimerData.of(StateNamespaces.global(), Instant.now(), TimeDomain.EVENT_TIME);

    TimerUpdate built = builder.deletedTimer(timer).setTimer(timer).build();

    assertThat(built.getSetTimers(), contains(timer));
    assertThat(built.getDeletedTimers(), emptyIterable());
  }

  @Test
  public void timerUpdateBuilderWithSetAfterBuildNotAddedToBuilt() {
    TimerUpdateBuilder builder = TimerUpdate.builder(null);
    TimerData timer = TimerData.of(StateNamespaces.global(), Instant.now(), TimeDomain.EVENT_TIME);

    TimerUpdate built = builder.build();
    builder.setTimer(timer);
    assertThat(built.getSetTimers(), emptyIterable());
    builder.build();
    assertThat(built.getSetTimers(), emptyIterable());
  }

  @Test
  public void timerUpdateBuilderWithDeleteAfterBuildNotAddedToBuilt() {
    TimerUpdateBuilder builder = TimerUpdate.builder(null);
    TimerData timer = TimerData.of(StateNamespaces.global(), Instant.now(), TimeDomain.EVENT_TIME);

    TimerUpdate built = builder.build();
    builder.deletedTimer(timer);
    assertThat(built.getDeletedTimers(), emptyIterable());
    builder.build();
    assertThat(built.getDeletedTimers(), emptyIterable());
  }

  @Test
  public void timerUpdateBuilderWithCompletedAfterBuildNotAddedToBuilt() {
    TimerUpdateBuilder builder = TimerUpdate.builder(null);
    TimerData timer = TimerData.of(StateNamespaces.global(), Instant.now(), TimeDomain.EVENT_TIME);

    TimerUpdate built = builder.build();
    builder.withCompletedTimers(ImmutableList.of(timer));
    assertThat(built.getCompletedTimers(), emptyIterable());
    builder.build();
    assertThat(built.getCompletedTimers(), emptyIterable());
  }

  @Test
  public void timerUpdateWithCompletedTimersNotAddedToExisting() {
    TimerUpdateBuilder builder = TimerUpdate.builder(null);
    TimerData timer = TimerData.of(StateNamespaces.global(), Instant.now(), TimeDomain.EVENT_TIME);

    TimerUpdate built = builder.build();
    assertThat(built.getCompletedTimers(), emptyIterable());
    assertThat(
        built.withCompletedTimers(ImmutableList.of(timer)).getCompletedTimers(), contains(timer));
    assertThat(built.getCompletedTimers(), emptyIterable());
  }

  private static Matcher<Instant> earlierThan(final Instant laterInstant) {
    return new BaseMatcher<Instant>() {
      @Override
      public boolean matches(Object item) {
        ReadableInstant instant = (ReadableInstant) item;
        return instant.isBefore(laterInstant);
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("earlier than ").appendValue(laterInstant);
      }
    };
  }

  private static Matcher<Instant> laterThan(final Instant shouldBeEarlier) {
    return new BaseMatcher<Instant>() {
      @Override
      public boolean matches(Object item) {
        ReadableInstant instant = (ReadableInstant) item;
        return instant.isAfter(shouldBeEarlier);
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("later than ").appendValue(shouldBeEarlier);
      }
    };
  }

  @SafeVarargs
  private final <T> CommittedBundle<T> timestampedBundle(
      PCollection<T> pc, TimestampedValue<T>... values) {
    UncommittedBundle<T> bundle = InProcessBundle.unkeyed(pc);
    for (TimestampedValue<T> value : values) {
      bundle.add(
          WindowedValue.timestampedValueInGlobalWindow(value.getValue(), value.getTimestamp()));
    }
    return bundle.commit(BoundedWindow.TIMESTAMP_MAX_VALUE);
  }

  @SafeVarargs
  private final <T> CommittedBundle<T> globallyWindowedBundle(PCollection<T> pc, T... values) {
    UncommittedBundle<T> bundle = InProcessBundle.unkeyed(pc);
    for (T value : values) {
      bundle.add(WindowedValue.valueInGlobalWindow(value));
    }
    return bundle.commit(BoundedWindow.TIMESTAMP_MAX_VALUE);
  }
}
