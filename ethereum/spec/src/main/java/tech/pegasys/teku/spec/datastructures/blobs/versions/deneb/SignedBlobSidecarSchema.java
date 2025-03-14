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

package tech.pegasys.teku.spec.datastructures.blobs.versions.deneb;

import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema2;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlockFields;
import tech.pegasys.teku.spec.datastructures.type.SszSignature;
import tech.pegasys.teku.spec.datastructures.type.SszSignatureSchema;

public class SignedBlobSidecarSchema
    extends ContainerSchema2<SignedBlobSidecar, BlobSidecar, SszSignature> {

  SignedBlobSidecarSchema(final BlobSidecarSchema blobSidecarSchema) {
    super(
        "SignedBlobSidecar",
        namedSchema("message", blobSidecarSchema),
        namedSchema(SignedBeaconBlockFields.SIGNATURE, SszSignatureSchema.INSTANCE));
  }

  public static SignedBlobSidecarSchema create(final BlobSidecarSchema blobSidecarSchema) {
    return new SignedBlobSidecarSchema(blobSidecarSchema);
  }

  public SignedBlobSidecar create(final BlobSidecar blobSidecar, final BLSSignature signature) {
    return new SignedBlobSidecar(this, blobSidecar, signature);
  }

  @Override
  public SignedBlobSidecar createFromBackingNode(final TreeNode node) {
    return new SignedBlobSidecar(this, node);
  }
}
