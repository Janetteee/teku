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

package tech.pegasys.teku.kzg;

import static com.google.common.base.Preconditions.checkArgument;
import static ethereum.ckzg4844.CKZG4844JNI.BYTES_PER_G1;
import static ethereum.ckzg4844.CKZG4844JNI.BYTES_PER_G2;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;

public record TrustedSetup(List<Bytes> g1Points, List<Bytes> g2Points) {

  public TrustedSetup {
    g1Points.forEach(this::validateG1Point);
    g2Points.forEach(this::validateG2Point);
  }

  private void validateG1Point(final Bytes g1Point) {
    checkArgument(g1Point.size() == BYTES_PER_G1, "Expected G1 point to be %s bytes", BYTES_PER_G1);
  }

  private void validateG2Point(final Bytes g2Point) {
    checkArgument(g2Point.size() == BYTES_PER_G2, "Expected G2 point to be %s bytes", BYTES_PER_G2);
  }
}
