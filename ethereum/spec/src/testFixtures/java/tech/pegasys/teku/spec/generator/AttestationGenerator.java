/*
 * Copyright Consensys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.spec.generator;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import tech.pegasys.teku.bls.BLS;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.bls.BLSTestUtil;
import tech.pegasys.teku.infrastructure.async.SyncAsyncRunner;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockSummary;
import tech.pegasys.teku.spec.datastructures.blocks.StateAndBlockSummary;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.Attestation.AttestationSchema;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.state.Committee;
import tech.pegasys.teku.spec.datastructures.state.CommitteeAssignment;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.EpochProcessingException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.SlotProcessingException;
import tech.pegasys.teku.spec.logic.common.util.EpochAttestationSchedule;
import tech.pegasys.teku.spec.logic.common.util.SlotAttestationSchedule;
import tech.pegasys.teku.spec.signatures.LocalSigner;

public class AttestationGenerator {
  private final Spec spec;
  private final List<BLSKeyPair> validatorKeys;
  private final BLSKeyPair randomKeyPair = BLSTestUtil.randomKeyPair(12345);

  public AttestationGenerator(final Spec spec, final List<BLSKeyPair> validatorKeys) {
    this.spec = spec;
    this.validatorKeys = validatorKeys;
  }

  public static int getSingleAttesterIndex(Attestation attestation) {
    return attestation.getAggregationBits().streamAllSetBits().findFirst().orElse(-1);
  }

  public static AttestationData diffSlotAttestationData(UInt64 slot, AttestationData data) {
    return new AttestationData(
        slot, data.getIndex(), data.getBeaconBlockRoot(), data.getSource(), data.getTarget());
  }

  /**
   * Groups passed attestations by their {@link
   * tech.pegasys.teku.spec.datastructures.operations.AttestationData} and aggregates attestations
   * in every group to a single {@link Attestation}
   *
   * @return a list of aggregated {@link Attestation}s with distinct {@link
   *     tech.pegasys.teku.spec.datastructures.operations.AttestationData}
   */
  public static List<Attestation> groupAndAggregateAttestations(List<Attestation> srcAttestations) {
    Collection<List<Attestation>> groupedAtt =
        srcAttestations.stream().collect(Collectors.groupingBy(Attestation::getData)).values();
    return groupedAtt.stream()
        .map(AttestationGenerator::aggregateAttestations)
        .collect(Collectors.toList());
  }

  private static Attestation aggregateAttestations(List<Attestation> srcAttestations) {
    Preconditions.checkArgument(!srcAttestations.isEmpty(), "Expected at least one attestation");
    final AttestationSchema attestationSchema = srcAttestations.get(0).getSchema();
    int targetBitlistSize =
        srcAttestations.stream().mapToInt(a -> a.getAggregationBits().size()).max().getAsInt();
    SszBitlist targetBitlist =
        srcAttestations.stream()
            .map(Attestation::getAggregationBits)
            .reduce(
                attestationSchema.getAggregationBitsSchema().ofBits(targetBitlistSize),
                SszBitlist::or,
                SszBitlist::or);
    BLSSignature targetSig =
        BLS.aggregate(
            srcAttestations.stream()
                .map(Attestation::getAggregateSignature)
                .collect(Collectors.toList()));

    return attestationSchema.create(targetBitlist, srcAttestations.get(0).getData(), targetSig);
  }

  public Attestation validAttestation(final StateAndBlockSummary blockAndState) {
    return validAttestation(blockAndState, blockAndState.getSlot());
  }

  public Attestation validAttestation(final StateAndBlockSummary blockAndState, final UInt64 slot) {
    return createAttestation(blockAndState, true, slot);
  }

  public Attestation attestationWithInvalidSignature(final StateAndBlockSummary blockAndState) {
    return createAttestation(blockAndState, false, blockAndState.getSlot());
  }

  private Attestation createAttestation(
      final StateAndBlockSummary blockAndState,
      final boolean withValidSignature,
      final UInt64 slot) {
    UInt64 assignedSlot = slot;

    Optional<Attestation> attestation = Optional.empty();
    while (attestation.isEmpty()) {
      Stream<Attestation> attestations =
          withValidSignature
              ? streamAttestations(blockAndState, assignedSlot)
              : streamInvalidAttestations(blockAndState, assignedSlot);
      attestation = attestations.findFirst();
      assignedSlot = assignedSlot.plus(UInt64.ONE);
    }

    return attestation.orElseThrow();
  }

  public List<Attestation> getAttestationsForSlot(final StateAndBlockSummary blockAndState) {
    return getAttestationsForSlot(blockAndState, blockAndState.getSlot());
  }

  public List<Attestation> getAttestationsForSlot(
      final StateAndBlockSummary blockAndState, final UInt64 slot) {

    return streamAttestations(blockAndState, slot).collect(Collectors.toList());
  }

  public List<Attestation> getAttestationsForSlot(
      final StateAndBlockSummary blockAndState,
      final UInt64 slot,
      final EpochAttestationSchedule epochCommitteeAssignments) {

    return streamAttestations(blockAndState, slot, epochCommitteeAssignments)
        .collect(Collectors.toList());
  }

  /**
   * Streams attestations for validators assigned to attest at {@code assignedSlot}, using the given
   * {@code headBlockAndState} as the calculated chain head.
   *
   * @param headBlockAndState The chain head to attest to
   * @param assignedSlot The assigned slot for which to produce attestations
   * @return A stream of valid attestations to produce at the assigned slot
   */
  public Stream<Attestation> streamAttestations(
      final StateAndBlockSummary headBlockAndState, final UInt64 assignedSlot) {
    return AttestationIterator.create(spec, headBlockAndState, assignedSlot, validatorKeys)
        .toStream();
  }

  public Stream<Attestation> streamAttestations(
      final StateAndBlockSummary headBlockAndState,
      final UInt64 assignedSlot,
      final EpochAttestationSchedule epochCommitteeAssignments) {
    return AttestationIterator.create(
            spec, headBlockAndState, assignedSlot, epochCommitteeAssignments, validatorKeys)
        .toStream();
  }

  /**
   * Streams invalid attestations for validators assigned to attest at {@code assignedSlot}, using
   * the given {@code headBlockAndState} as the calculated chain head.
   *
   * @param headBlockAndState The chain head to attest to
   * @param assignedSlot The assigned slot for which to produce attestations
   * @return A stream of invalid attestations produced at the assigned slot
   */
  private Stream<Attestation> streamInvalidAttestations(
      final StateAndBlockSummary headBlockAndState, final UInt64 assignedSlot) {
    return AttestationIterator.createWithInvalidSignatures(
            spec, headBlockAndState, assignedSlot, validatorKeys, randomKeyPair)
        .toStream();
  }

  /**
   * Iterates through valid attestations with the supplied head block, produced at the given
   * assigned slot.
   */
  private static class AttestationIterator implements Iterator<Attestation> {
    private final Spec spec;
    // The latest block being attested to
    private final BeaconBlockSummary headBlock;
    // The latest state processed through to the current slot
    private final BeaconState headState;
    // The assigned slot to generate attestations for
    private final UInt64 assignedSlot;
    // The epoch containing the assigned slot
    private final UInt64 assignedSlotEpoch;
    // Validator keys
    private final List<BLSKeyPair> validatorKeys;
    private final Function<Integer, BLSKeyPair> validatorKeySupplier;

    private Optional<Attestation> nextAttestation = Optional.empty();
    private int currentValidatorIndex = 0;

    private final Optional<SlotAttestationSchedule> maybeSlotAttestationSchedule;

    private AttestationIterator(
        final Spec spec,
        final StateAndBlockSummary headBlockAndState,
        final UInt64 assignedSlot,
        final List<BLSKeyPair> validatorKeys,
        final Function<Integer, BLSKeyPair> validatorKeySupplier,
        final Optional<SlotAttestationSchedule> maybeSlotAttestationSchedule) {
      this.spec = spec;
      this.headBlock = headBlockAndState;
      this.headState = generateHeadState(headBlockAndState.getState(), assignedSlot);
      this.validatorKeys = validatorKeys;
      this.assignedSlot = assignedSlot;
      this.assignedSlotEpoch = spec.computeEpochAtSlot(assignedSlot);
      this.validatorKeySupplier = validatorKeySupplier;
      this.maybeSlotAttestationSchedule = maybeSlotAttestationSchedule;
      generateNextAttestation();
    }

    public static AttestationIterator create(
        final Spec spec,
        final StateAndBlockSummary headBlockAndState,
        final UInt64 assignedSlot,
        final EpochAttestationSchedule epochCommitteeAssignments,
        final List<BLSKeyPair> validatorKeys) {
      return new AttestationIterator(
          spec,
          headBlockAndState,
          assignedSlot,
          validatorKeys,
          validatorKeys::get,
          Optional.of(epochCommitteeAssignments.atSlot(assignedSlot)));
    }

    private BeaconState generateHeadState(final BeaconState state, final UInt64 slot) {
      if (state.getSlot().equals(slot)) {
        return state;
      }

      try {
        return spec.processSlots(state, slot);
      } catch (EpochProcessingException | SlotProcessingException e) {
        throw new IllegalStateException(e);
      }
    }

    public static AttestationIterator create(
        final Spec spec,
        final StateAndBlockSummary headBlockAndState,
        final UInt64 assignedSlot,
        final List<BLSKeyPair> validatorKeys) {
      return new AttestationIterator(
          spec,
          headBlockAndState,
          assignedSlot,
          validatorKeys,
          validatorKeys::get,
          Optional.empty());
    }

    public static AttestationIterator createWithInvalidSignatures(
        final Spec spec,
        final StateAndBlockSummary headBlockAndState,
        final UInt64 assignedSlot,
        final List<BLSKeyPair> validatorKeys,
        final BLSKeyPair invalidKeyPair) {
      return new AttestationIterator(
          spec,
          headBlockAndState,
          assignedSlot,
          validatorKeys,
          __ -> invalidKeyPair,
          Optional.empty());
    }

    public Stream<Attestation> toStream() {
      final Spliterator<Attestation> split =
          Spliterators.spliteratorUnknownSize(
              this, Spliterator.IMMUTABLE | Spliterator.DISTINCT | Spliterator.NONNULL);

      return StreamSupport.stream(split, false);
    }

    @Override
    public boolean hasNext() {
      return nextAttestation.isPresent();
    }

    @Override
    public Attestation next() {
      if (nextAttestation.isEmpty()) {
        throw new NoSuchElementException();
      }
      final Attestation attestation = nextAttestation.get();
      generateNextAttestation();
      return attestation;
    }

    private void generateNextAttestation() {
      nextAttestation = Optional.empty();

      if (maybeSlotAttestationSchedule.isPresent()) {
        generateNextAttestationFromSchedule();
        return;
      }

      int lastProcessedValidatorIndex = currentValidatorIndex;
      for (int validatorIndex = currentValidatorIndex;
          validatorIndex < validatorKeys.size();
          validatorIndex++) {
        lastProcessedValidatorIndex = validatorIndex;
        final Optional<CommitteeAssignment> maybeAssignment =
            spec.getCommitteeAssignment(headState, assignedSlotEpoch, validatorIndex);

        if (maybeAssignment.isEmpty()) {
          continue;
        }

        final CommitteeAssignment assignment = maybeAssignment.get();
        if (!assignment.getSlot().equals(assignedSlot)) {
          continue;
        }

        final IntList committeeIndices = assignment.getCommittee();
        final UInt64 committeeIndex = assignment.getCommitteeIndex();
        final Committee committee = new Committee(committeeIndex, committeeIndices);
        final int indexIntoCommittee = committeeIndices.indexOf(validatorIndex);
        final AttestationData genericAttestationData =
            spec.getGenericAttestationData(assignedSlot, headState, headBlock, committeeIndex);
        final BLSKeyPair validatorKeyPair = validatorKeySupplier.apply(validatorIndex);
        nextAttestation =
            Optional.of(
                createAttestation(
                    headState,
                    validatorKeyPair,
                    indexIntoCommittee,
                    committee,
                    genericAttestationData));
        break;
      }

      currentValidatorIndex = lastProcessedValidatorIndex + 1;
    }

    private void generateNextAttestationFromSchedule() {
      final SlotAttestationSchedule schedule = maybeSlotAttestationSchedule.orElseThrow();
      if (schedule.isDone()) {
        nextAttestation = Optional.empty();
      } else {
        final UInt64 currentCommittee = schedule.getCurrentCommittee();
        final IntList indices = schedule.getCommittee(currentCommittee);
        final Integer indexIntoCommittee = schedule.getIndexIntoCommittee();
        final Integer validatorIndex = indices.getInt(indexIntoCommittee);
        final Committee cc = new Committee(currentCommittee, indices);
        final BLSKeyPair validatorKeyPair = validatorKeySupplier.apply(validatorIndex);
        final AttestationData genericAttestationData =
            spec.getGenericAttestationData(assignedSlot, headState, headBlock, currentCommittee);
        nextAttestation =
            Optional.of(
                createAttestation(
                    headState, validatorKeyPair, indexIntoCommittee, cc, genericAttestationData));
        schedule.next();
      }
    }

    private Attestation createAttestation(
        BeaconState state,
        BLSKeyPair attesterKeyPair,
        int indexIntoCommittee,
        Committee committee,
        AttestationData attestationData) {
      int committeeSize = committee.getCommitteeSize();
      final AttestationSchema attestationSchema =
          spec.atSlot(attestationData.getSlot()).getSchemaDefinitions().getAttestationSchema();
      SszBitlist aggregationBitfield =
          getAggregationBits(attestationSchema, committeeSize, indexIntoCommittee);

      BLSSignature signature =
          new LocalSigner(spec, attesterKeyPair, SyncAsyncRunner.SYNC_RUNNER)
              .signAttestationData(attestationData, state.getForkInfo())
              .join();
      return attestationSchema.create(aggregationBitfield, attestationData, signature);
    }

    private SszBitlist getAggregationBits(
        AttestationSchema attestationSchema, int committeeSize, int indexIntoCommittee) {
      // Create aggregation bitfield
      return attestationSchema.getAggregationBitsSchema().ofBits(committeeSize, indexIntoCommittee);
    }
  }
}
