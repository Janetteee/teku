/*
 * Copyright Consensys Software Inc., 2023
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

package tech.pegasys.teku.spec.datastructures.builder.versions.deneb;

import java.util.function.Consumer;
import org.apache.tuweni.units.bigints.UInt256;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema4;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt256;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.builder.BlindedBlobsBundle;
import tech.pegasys.teku.spec.datastructures.builder.BlindedBlobsBundleSchema;
import tech.pegasys.teku.spec.datastructures.builder.BuilderBid;
import tech.pegasys.teku.spec.datastructures.builder.BuilderBidBuilder;
import tech.pegasys.teku.spec.datastructures.builder.BuilderBidSchema;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeaderSchema;
import tech.pegasys.teku.spec.datastructures.type.SszPublicKey;
import tech.pegasys.teku.spec.datastructures.type.SszPublicKeySchema;

public class BuilderBidSchemaDeneb
    extends ContainerSchema4<
        BuilderBidDenebImpl, ExecutionPayloadHeader, BlindedBlobsBundle, SszUInt256, SszPublicKey>
    implements BuilderBidSchema<BuilderBidDenebImpl> {

  public BuilderBidSchemaDeneb(
      final ExecutionPayloadHeaderSchema<?> executionPayloadHeaderSchema,
      final BlindedBlobsBundleSchema blindedBlobsBundleSchema) {
    super(
        "BuilderBidDeneb",
        namedSchema(
            "header", SszSchema.as(ExecutionPayloadHeader.class, executionPayloadHeaderSchema)),
        namedSchema("blinded_blobs_bundle", blindedBlobsBundleSchema),
        namedSchema("value", SszPrimitiveSchemas.UINT256_SCHEMA),
        namedSchema("pubkey", SszPublicKeySchema.INSTANCE));
  }

  public BuilderBidDeneb create(
      final ExecutionPayloadHeader executionPayloadHeader,
      final BlindedBlobsBundle blindedBlobsBundle,
      final UInt256 value,
      final BLSPublicKey publicKey) {
    return new BuilderBidDenebImpl(
        this,
        executionPayloadHeader,
        blindedBlobsBundle,
        SszUInt256.of(value),
        new SszPublicKey(publicKey));
  }

  @Override
  public BuilderBidDenebImpl createFromBackingNode(final TreeNode node) {
    return new BuilderBidDenebImpl(this, node);
  }

  @Override
  public BuilderBid createBuilderBid(final Consumer<BuilderBidBuilder> builderConsumer) {
    final BuilderBidBuilderDeneb builder = new BuilderBidBuilderDeneb().schema(this);
    builderConsumer.accept(builder);
    return builder.build();
  }
}
