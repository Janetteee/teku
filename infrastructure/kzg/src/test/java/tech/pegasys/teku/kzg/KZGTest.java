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

package tech.pegasys.teku.kzg;

import static ethereum.ckzg4844.CKZG4844JNI.BLS_MODULUS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.Streams;
import com.google.common.io.Resources;
import ethereum.ckzg4844.CKZG4844JNI;
import ethereum.ckzg4844.CKZGException;
import ethereum.ckzg4844.CKZGException.CKZGError;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes48;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.pegasys.teku.kzg.ckzg4844.CKZG4844;
import tech.pegasys.teku.kzg.trusted_setups.TrustedSetups;

public final class KZGTest {

  private static final int FIELD_ELEMENTS_PER_BLOB =
      CKZG4844JNI.Preset.MINIMAL.fieldElementsPerBlob;
  private static final int RANDOM_SEED = 5566;
  private static final Random RND = new Random(RANDOM_SEED);
  private static final String TRUSTED_SETUP_PATH = "minimal/trusted_setup.txt";

  private static KZG kzg;

  @BeforeAll
  public static void setUp() {
    // test initializing with invalid fieldElementsPerBlob
    final KZGException exception =
        assertThrows(KZGException.class, () -> CKZG4844.createInstance(5));
    assertThat(exception)
        .hasMessage("C-KZG-4844 library can't be initialized with 5 fieldElementsPerBlob.");
    kzg = CKZG4844.createInstance(FIELD_ELEMENTS_PER_BLOB);
  }

  @AfterEach
  public void cleanUpIfNeeded() {
    try {
      kzg.freeTrustedSetup();
    } catch (final KZGException ex) {
      // NOOP
    }
  }

  @Test
  public void testCreatingInstanceWithDifferentFieldElementsPerBlob_shouldThrowException() {
    final KZGException exception =
        assertThrows(
            KZGException.class,
            () -> CKZG4844.createInstance(CKZG4844JNI.Preset.MAINNET.fieldElementsPerBlob));
    assertThat(exception)
        .hasMessage(
            "Can't reinitialize C-KZG-4844 library with a different value for fieldElementsPerBlob.");
  }

  @Test
  public void testKzgLoadSameTrustedSetupTwice_shouldNotThrowException() {
    loadTrustedSetup();
    loadTrustedSetup();
  }

  @Test
  public void testKzLoadDifferentTrustedSetupTwice_shouldThrowException() {
    loadTrustedSetup();
    assertThrows(
        KZGException.class, () -> kzg.loadTrustedSetup("mainnet/trusted_setup-not-existing.txt"));
  }

  @Test
  public void testKzgFreeTrustedSetupTwice_shouldThrowException() {
    loadTrustedSetup();
    kzg.freeTrustedSetup();
    assertThrows(KZGException.class, kzg::freeTrustedSetup);
  }

  @Test
  public void testUsageWithoutLoadedTrustedSetup_shouldThrowException() {
    final List<KZGException> exceptions =
        List.of(
            assertThrows(
                KZGException.class,
                () ->
                    kzg.verifyBlobKzgProofBatch(
                        List.of(Bytes.fromHexString("0x", 128)),
                        List.of(KZGCommitment.infinity()),
                        List.of(KZGProof.INFINITY))),
            assertThrows(KZGException.class, () -> kzg.blobToKzgCommitment(Bytes.EMPTY)),
            assertThrows(
                KZGException.class,
                () -> kzg.computeBlobKzgProof(Bytes.EMPTY, KZGCommitment.infinity())));

    assertThat(exceptions)
        .allSatisfy(
            exception -> assertThat(exception).cause().hasMessage("Trusted Setup is not loaded."));
  }

  @Test
  public void testComputingAndVerifyingBatchProofs() {
    loadTrustedSetup();
    final int numberOfBlobs = 4;
    final List<Bytes> blobs = getSampleBlobs(numberOfBlobs);
    final List<KZGCommitment> kzgCommitments =
        blobs.stream().map(kzg::blobToKzgCommitment).collect(Collectors.toList());
    final List<KZGProof> kzgProofs =
        Streams.zip(
                kzgCommitments.stream(),
                blobs.stream(),
                (kzgCommitment, blob) -> kzg.computeBlobKzgProof(blob, kzgCommitment))
            .collect(Collectors.toList());
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, kzgProofs)).isTrue();

    assertThat(
            kzg.verifyBlobKzgProofBatch(getSampleBlobs(numberOfBlobs), kzgCommitments, kzgProofs))
        .isFalse();
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, getSampleCommitments(numberOfBlobs), kzgProofs))
        .isFalse();
    final List<KZGProof> invalidProofs =
        getSampleBlobs(numberOfBlobs).stream()
            .map((Bytes blob) -> kzg.computeBlobKzgProof(blob, kzg.blobToKzgCommitment(blob)))
            .collect(Collectors.toList());
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, invalidProofs)).isFalse();
  }

  @Test
  public void testVerifyingEmptyBatch() {
    loadTrustedSetup();
    assertThat(kzg.verifyBlobKzgProofBatch(List.of(), List.of(), List.of())).isTrue();
  }

  @Test
  public void testComputingAndVerifyingBatchSingleProof() {
    loadTrustedSetup();
    final int numberOfBlobs = 1;
    final List<Bytes> blobs = getSampleBlobs(numberOfBlobs);
    final List<KZGCommitment> kzgCommitments =
        blobs.stream().map(kzg::blobToKzgCommitment).collect(Collectors.toList());
    final List<KZGProof> kzgProofs =
        Streams.zip(
                kzgCommitments.stream(),
                blobs.stream(),
                (kzgCommitment, blob) -> kzg.computeBlobKzgProof(blob, kzgCommitment))
            .collect(Collectors.toList());
    assertThat(kzgProofs.size()).isEqualTo(1);
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, kzgProofs)).isTrue();

    assertThat(
            kzg.verifyBlobKzgProofBatch(getSampleBlobs(numberOfBlobs), kzgCommitments, kzgProofs))
        .isFalse();
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, getSampleCommitments(numberOfBlobs), kzgProofs))
        .isFalse();
    final List<KZGProof> invalidProofs =
        getSampleBlobs(numberOfBlobs).stream()
            .map((Bytes blob) -> kzg.computeBlobKzgProof(blob, kzg.blobToKzgCommitment(blob)))
            .collect(Collectors.toList());
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, invalidProofs)).isFalse();
  }

  @Test
  public void testVerifyingBatchProofsThrowsIfSizesDoesntMatch() {
    loadTrustedSetup();
    final int numberOfBlobs = 4;
    final List<Bytes> blobs = getSampleBlobs(numberOfBlobs);
    final List<KZGCommitment> kzgCommitments =
        blobs.stream().map(kzg::blobToKzgCommitment).collect(Collectors.toList());
    final List<KZGProof> kzgProofs =
        Streams.zip(
                kzgCommitments.stream(),
                blobs.stream(),
                (kzgCommitment, blob) -> kzg.computeBlobKzgProof(blob, kzgCommitment))
            .collect(Collectors.toList());
    final KZGException kzgException1 =
        assertThrows(
            KZGException.class,
            () -> kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, List.of(kzgProofs.get(0))));
    final KZGException kzgException2 =
        assertThrows(
            KZGException.class,
            () -> kzg.verifyBlobKzgProofBatch(blobs, List.of(kzgCommitments.get(0)), kzgProofs));
    final KZGException kzgException3 =
        assertThrows(
            KZGException.class,
            () -> kzg.verifyBlobKzgProofBatch(List.of(blobs.get(0)), kzgCommitments, kzgProofs));

    Stream.of(kzgException1, kzgException2, kzgException3)
        .forEach(
            ex ->
                assertThat(ex)
                    .cause()
                    .isInstanceOf(CKZGException.class)
                    .hasMessageMatching(
                        "Invalid .+ size. Expected \\d+ bytes but got \\d+. \\(C_KZG_BADARGS\\)"));
  }

  @ParameterizedTest(name = "blob={0}")
  @ValueSource(
      strings = {
        "0x0d2024ece3e004271319699b8b00cc010628b6bc0be5457f031fb1db0afd3ff8",
        "0x",
        "0x925668a49d06f4"
      })
  public void testComputingProofWithIncorrectLengthBlobDoesNotCauseSegfault(final String blobHex) {
    loadTrustedSetup();
    final Bytes blob = Bytes.fromHexString(blobHex);

    final KZGException kzgException =
        assertThrows(
            KZGException.class, () -> kzg.computeBlobKzgProof(blob, kzg.blobToKzgCommitment(blob)));

    assertThat(kzgException)
        .cause()
        .satisfies(
            cause -> {
              // non-canonical blobs
              assertThat(cause).isInstanceOf(CKZGException.class);
              final CKZGException cryptoException = (CKZGException) cause;
              assertThat(cryptoException.getError()).isEqualTo(CKZGError.C_KZG_BADARGS);
              assertThat(cryptoException.getErrorMessage())
                  .contains("Invalid blob size. Expected 128 bytes but got");
            });
  }

  @ParameterizedTest(name = "trusted_setup={0}")
  @ValueSource(
      strings = {
        "broken/trusted_setup_g1_length.txt",
        "broken/trusted_setup_g2_length.txt",
        "broken/trusted_setup_g2_bytesize.txt"
      })
  public void incorrectTrustedSetupFilesShouldThrow(final String path) {
    final String trustedSetup = Resources.getResource(TrustedSetups.class, path).toExternalForm();
    final Throwable cause =
        assertThrows(KZGException.class, () -> kzg.loadTrustedSetup(trustedSetup)).getCause();
    assertThat(cause.getMessage()).contains("Failed to parse trusted setup file");
  }

  @ParameterizedTest(name = "trusted_setup={0}")
  @ValueSource(
      strings = {"mainnet/trusted_setup_monomial.txt", "minimal/trusted_setup_monomial.txt"})
  public void monomialTrustedSetupFilesShouldThrow(final String path) {
    final String trustedSetup = Resources.getResource(TrustedSetups.class, path).toExternalForm();
    final KZGException kzgException =
        assertThrows(KZGException.class, () -> kzg.loadTrustedSetup(trustedSetup));
    assertThat(kzgException.getMessage()).contains("Failed to load trusted setup");
    assertThat(kzgException.getCause().getMessage())
        .contains("There was an error while loading the Trusted Setup. (C_KZG_BADARGS)");
  }

  @Test
  public void testInvalidLengthG2PointInNewTrustedSetup() {
    assertThatThrownBy(() -> new TrustedSetup(List.of(), List.of(Bytes.fromHexString(""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected G2 point to be 96 bytes");
  }

  @Test
  public void testComputingAndVerifyingProofWithTrustedSetupInitializedFromObject() {
    kzg.loadTrustedSetup(
        new TrustedSetup(
            List.of(
                Bytes48.fromHexString(
                    "91131b2e3c1e5f0b51df8970e67080032f411571b66d301436c46f25bbfddf9ca16756430dc470bdb0d85b47fedcdbc1"),
                Bytes48.fromHexString(
                    "934d35b2a46e169915718b77127b0d4efbacdad7fdde4593af7d21d37ebcb77fe6c8dde6b8a9537854d70ef1f291a585"),
                Bytes48.fromHexString(
                    "9410ca1d0342fe7419f02194281df45e1c1ff42fd8b439de5644cc312815c21ddd2e3eeb63fb807cf837e68b76668bd5"),
                Bytes48.fromHexString(
                    "b163df7e9baeb60f69b6ee5faa538c3a564b62eb8cde6a3616083c8cb2171eedd583c9143e7e916df59bf27da5e024e8")),
            List.of(
                Bytes.fromHexString(
                    "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"),
                Bytes.fromHexString(
                    "99aca9fb2f7760cecb892bf7262c176b334824f5727f680bba701a33e322cb6667531410dfc7c8e4321a3f0ea8af48cb1436638a2093123f046f0f504cc2a864825542873edbbc5d7ed17af125a4f2cf6433c6f4f61b81173726981dd989761d"),
                Bytes.fromHexString(
                    "88e2e982982bf8231e747e9dfcd14c05bd02623d1332734d2af26246c6869fb56ee6c994843f593178a040495ba61f4a083b0e18110b1d9f5224783d8f9a895e8ee744e87929430e9ba96bd29251cbf61240b256d1525600f3d562894d93d659"),
                Bytes.fromHexString(
                    "a2d33775e3d9e6af0d1b27d389e6c021a578e617a3d6627686db6288d4b3dffd7a847a00f7ef01828b7f42885b660e4204923402aca18fbae74ccd4e9c50dd8c2281b38dc09c022342ed1ac695d53f7081cb21f05fdfc0a3508c04759196fcd3"),
                Bytes.fromHexString(
                    "af565445d2ad54c83a75c40e8895f5ad7219a8c728bce9d58d7a83716e095432993ebbd3f6911c66415a6f920d1a4d171478509b54a114308a020b33bf4487a7a8d0aa76ae4676a9b54e765a680f562d3a4fcb2e92c58b14b49b5b2917cc258f"),
                Bytes.fromHexString(
                    "8aa99cfaf514cef4801599cadd780d222194ca1ad69a34779c2bcfda93e5dbeb931e13914421b5809a6c81f12cf7038b04a35257cc9e94c33761e68565b1274aa6a6f9d66477229747a66b308b138f92aa4326a3bf23df65a1fe33b3b289bfe1"),
                Bytes.fromHexString(
                    "99ba36d8b4f56bde026099278548b1afc0a987cbd7c9baa51fc8e6cbb8237a17636f1a44a385cec69b05a5802059956a11fe793cabb939c38800f9c239ca2518e898ade1ec2513c9ee492071a35aabd78182392a09123d28dbc233313c9120c4"),
                Bytes.fromHexString(
                    "a7dc40c36afccb30a2eaff250860b28b227c195cf05674704c567d77d6655c446ae835f8fc8667e71147ab02afcb2dad0babe60cbfa37d7c2cddc68d2dec54f28a4142f8353590a3902d5ddaa22066ab563dd1435dda83f276387b9767d69120"),
                Bytes.fromHexString(
                    "939e6cc97a8b88572852a5b7f25e4838556307f60aeafb5d2b6961edbcafd4b48cb6ac980ffbacf4be963f324ba81e3d12de4f1459d8c746d0762c66ae1b166027f7fbe641d9c48f3c7d97b06d956b0be51dcc9aab65f3e99e1388e63bdd79f9"),
                Bytes.fromHexString(
                    "b391e156541dfd4003d1697cdb7ec815b309807320574906b2e652ef0175828b356d215cd374b1b34d9f470b3fa0e643113e67b2273268f922f04f072cfb89008358185b25cd631f82911a3f20f90f75758ffb99bebb8076458ae1e9d1ae898c"),
                Bytes.fromHexString(
                    "b9ac9c84934cc2a85c876eff65577e1dfce1935cd6392c877dd881a7d2f5c3e9344f28c04f90c62a6db4237ca00f9e0d00cb5f63e3f060fc7303916e19273b6fe455f331cabbe2fe5a22d584484f0d4176120fec9819fbb0a01e6d38695acfcd"),
                Bytes.fromHexString(
                    "88209eb030c5d78734bf2c2a5c539653fd3c24b4c08e624f9ddc4a6550efbdc1054a56eb0c807595aad6de56fda326aa196d032a8b4b48d40140a2d77df3c7243eda6507936389a321a5811eb38e32ee433c788deeae1eb928b00940e2944bcc"),
                Bytes.fromHexString(
                    "a8632ddc9cf7cbc1e8b74a05b7d4a89618c64afe30367ca0c9550ae7d320bf4e51c5a69e1501a1d8bee4240d13d7835501aa39fdc401a74f4d5734e268a7ce29a1fcfdb0a8bc64e0dd4a9e8578d6985bc2bc6a3764ce7a3703f6fb2e52557a2b"),
                Bytes.fromHexString(
                    "a037ac67e8bb6f4193ac967e05d080a489f58ef8d3d30a89798246f3e4936121ee445b03e410a09e8ebc0db2e2477d110aad0ade99b0887f1eb016e750f42135866907f150bd6f4f99a8cb94281474166874808ebe03b118c5daab16dafdc38b"),
                Bytes.fromHexString(
                    "a50d9143116bffa3b237da8e1805327e81e9cd25e658289bd727d5f9e0020172cc8690dcfe31a240e5cbc48353b88c4908baa1dd7320165556e0aa633f62fcbe7870222d345a3bbcdb7ab6c07f0fd86be559964afabf56f0a8cbc0b4b91d477e"),
                Bytes.fromHexString(
                    "afa988ea6fa4f40c5ad07d2d580d29025ddf56d6ef1171a8b8de3464203f70b97d6f5ace72747345204b35150e06154d1477516a989ce8eea7871cc0d0de00a077c0fb23ad4837e409d0b885bf3f2dde11a30fa6273d662e68e09f461e52932f"),
                Bytes.fromHexString(
                    "97fa1a943ed8b81574304a3d03f4f15907f6e6e0cd36a66bd2ad2c75afafc70a61d3ff69b77ebe4dae9ca0fcedef80081062705e60bbb6ea0f1f398c84d2f8e4a3ac142ac66426c21ad5e9994ebbcc406af474c4aec5e32fadcb21875af7c9f1"),
                Bytes.fromHexString(
                    "b30a564614493886f14a5dd71c89457504f8c59a7ac01b665ed167e9a8f9ee5832198fd319ecd234196ee57031bdf3840bd5a923e203a1938bc795c704b5285389750e1fd10d7050061ba19db00a60a2c0384a7d661d7d48ebe6962272230859"),
                Bytes.fromHexString(
                    "84c8dea942cfae71cb02e705ec496d967425793ce8812e7ee53c2f23713abeaff566a658cd1c73dfd18187d16253a6ee0a623e82cd18e31cd1a1875d19c078835dc9292e141686150a88065226ada264740143e87c03a0f6c4da8c187438ebf4"),
                Bytes.fromHexString(
                    "8c3abae8aed60338f8c4ff80aab22f8a2ae56756a93566c906f490a97151d34a1c3318054e1c494c60cc53327ad86a2d02c6c76a406726ce4f88635bc32eff0db0b61762dc518b95fa8da82e87e4bf3de54f1d72180ef53ed7bc5413e6a9a510"),
                Bytes.fromHexString(
                    "a328230c92a6b1cef6a444bcb64edb992f71e3d7b93f0b6b8b408ba7c908db746d92ddb2c7588bab438ef3bc61be1c2f0dfc86ba2ff514b42b35c80f89b2e780f813ea1dfb977fbded2cd9b553b747fa952e227ebd8f071163d421fc337f04c9"),
                Bytes.fromHexString(
                    "b482cab423cd5f1c5df036070aade7aa016283d69619d664025c3feab866a0a5691d344b2ee2bedc5dedd1f9a73eae16003a3827c9e5bbe22ded32d848fba840ffad1141ad158f5c40bc8ae0d03781b9705d851a7f1391b096c576c0f4f2a6b0"),
                Bytes.fromHexString(
                    "919ee1df27fabcb21237a1b7b98f53d41d849e1b6a8f9e28c3fae2841c6b5a250e4041c737e6725476e5cd715e34d3880f58d80f61efaabc261bdc703e8750f48a923e9bf8980931b9fd9e40014c66c54b3e7c98241d76d1aa47af43313a65a1"),
                Bytes.fromHexString(
                    "ac94830145dbe9a8f7e6e0fc1f5fb454502d22abcafdc2dd96c6933c604461fa83b2b37385f4bc454875a02a6d4157841250956783515d11c7456e7f11b745f12856d89f5feedaf6a61a483a6c33a21cd2ba0c18eb41a1a2e7fc33bb53e4c570"),
                Bytes.fromHexString(
                    "b209c699f1233735c5bb4bce848e4365fd76651ae2184d2279a90df0c2f69ffa2a24d84a9b9f274021072953c0d65e1a0202d490d6c37186af240114e445d87bff754b4824937e4f2c90a574061b1c4910fed88d90f698025a2a264e656cb8a4"),
                Bytes.fromHexString(
                    "93320dc0576b0d069de63c40e5582b4486d9adf5e69e77e3ebaf3da26976fe42147a65051501bc8383f99e7ba75479c70a6726c2cd08bf98c7481f1f819712292d833a879f21a1221a9610bc748fb5e911055122fdb4055cdc84e8bfe0f4df9b"),
                Bytes.fromHexString(
                    "a4380b240e998cdf668591f71a0c88ed143b0185a920787627ce65095f8223dc606fa5bce93377af100de92d663e675c0736d7f1973603a84a5c4162fb5e01c88c7493503ae1d7e9fbe8ece9b418397d68c21eeb88dae226e09875d372c646dd"),
                Bytes.fromHexString(
                    "aab48517d69135a16b36b685adfe9b2544a709135a21ba3e75981a2cba4ec81d1fe28ac0f72fde0c0001c15300ed6a810f58d3117bdd58d0149751d6508cf8a1a1ff7b63dd02d2730a9d6fe96c77c502fe8ed46d50a181ec4bb35e37dfbd6af4"),
                Bytes.fromHexString(
                    "8277265fe75ab89ce4ec65b33fb4084bec0a56d81faf2f7a9070d2ca3065678e03a790350eba56323a54e0285bc32fe8007d5259740fde226e16cbde8354eacd562294eb9b7f727ed72ffbdad86f467cf057c737b34b80a41deb92634ed866f5"),
                Bytes.fromHexString(
                    "aa40a24cb2ebe606d969392c03020070f044c95088d80f57f771b837c048342d2cd3474600d7660441090ffb8d2ffb7f0eddd67eb378e3e1477a6ba0bc38096d5d2d3355bc8b60f605f57f0c1899da591457440352381d2b38c0aa9acc7fe419"),
                Bytes.fromHexString(
                    "80815d10685808cb630820629bcd2fa9041c9b74433630c0b9c1b7f7e8edf1440b520217f76ec9a50c125cf4438aa66006a1928a9ed2321da7ea325c3d56b65462b72118ca2c99a0ea733aa11da9abbeda6cc71ffeed301ae70213a29e697dcd"),
                Bytes.fromHexString(
                    "ac235d079f91b00b1fead7523da8f73a5409fa8970907af0c5d5e4c6a0996dccfcdb0d822d08c7fbc0c24799457d011d04312d20831825f23cf988141056a6814c8a1cac9efe37bdcbfa272aed24cd92810fea7c49b0d07683a5c53643872179"),
                Bytes.fromHexString(
                    "b8aa59534d75fa5ac1c2c3f963bf73899aff5210059dbde8a8635561c6249e5143affee3bd2fd57575213b52d9a73d5702525867a7dcbb1d0a49b98c2925556fc5463ff0209742046a24ab29e74257d6419401093cc4371944d811cc300b6a67"),
                Bytes.fromHexString(
                    "80bbfc5b816eea29a6d84e2217dee4d547306994d39e5592515e1b0807b67fe960d1d5addb0ff1a20c158bdb294c04bf093d28996121845a2c9268e2c9ac0f4067e889c6aaca62f8535d35b45036954bd069e3afa84f04721538c26003304c20"),
                Bytes.fromHexString(
                    "a535c17d0e151d0e03d42dd58ba8c715bee3fabca2890e0e016071d34184b6b34e770d2be29c8ec76b69bcc471d50f4d043c2c240e9b93a81cff7ee2724e02018dfd9b534e40be641fdb4884abcd83b76f517557ffba508f1ba2f56313f4de94"),
                Bytes.fromHexString(
                    "b237eb7465df0d325a3aa58269be2627e4978f9863f4f100ed4c303cb1f6549e606f2e3c9180824d8049191965c8dacd0a0c76cc56cb22cf1bcfdb39372c8aa29b4f7b34582b1719e6bd59c930d87d5ccd838743b585d6e229d5ed42337315c0"),
                Bytes.fromHexString(
                    "805c335a2a9d2de30809cf30808ef836d88e9453c510716f01696f14c72dd60505eca8f128970edc8e63a9aa1f8792ac0dd50dcc84fbf4cc8b32349c682a6a27bc7551c7aa273a94c1606d07710188d93579afe3be1781bded15a34ed6047922"),
                Bytes.fromHexString(
                    "b25dadf385ddd3c39bcb0a014d3d4f66127946b1aceae8809e3a03d66cc25e27142ca108316391f857fe82fdea4db2520cc73793b695eafbf3ade00ef7ec747b0457e49303f5e1a370f5263b436566fe24a0876e5fe088238c7be37a0718d65f"),
                Bytes.fromHexString(
                    "b0f753081cabe2c8fce73aba82ff67dbc9842598b3e7fa3ce2a1f534536f8ac63c532fe66552ac6b7adb28c73ed4c8a4184849be7c1756a4681ce29ebf5e1c3aa806b667ee6bd68f6397aba3215dc1caec6742f21d681e32cd1160d6a3b1d7ee"),
                Bytes.fromHexString(
                    "b798771eeb3d7a17c62ba5916cc034bba870da6b1ac14c2e1cae71af3ad4e0c0d1ff983f691e0e55289d5a33b131f2ec12430c9566dd71f4d8be9c79155357a5c30c5efcfd75bbe1bb6d5ada4d50604ea49ed838d3641f268ca6e25c9c4b6b72"),
                Bytes.fromHexString(
                    "b52554c017388b099804abbe565346591a086d9979e10140ddaccc0a3680e506db775d7cbeafde67563adf0f09f5c2420caf19629f4e8f03e6fe02e9416ecd5269989e482b90004a083967d1141387eb74865bac6bd17e7a6d5f58225e52d4b7"),
                Bytes.fromHexString(
                    "b520ff694520919023d44d53f98a7de2f78ff37b2d9193dcaa35556a6a0febf767781a4c961dce7c804bfdf81935f8f0082865253da52e79dfa1c5ff74d61495b2da76e167d46114709e877a7791a3a95e33a42f56b83f5f5afe271c67ae997c"),
                Bytes.fromHexString(
                    "b721401983440797a03d5b99f2088a0b249aa911969c34dd6c615b0060325da555d2ad99d931170c0868b0488a2234a4114cc0013d5163b833f5c45c5eb536421c016cf85788390176bb2dc4c196d6be26bbbfceae048b82f0d8039222e71c94"),
                Bytes.fromHexString(
                    "acd9d833ba0a8cbd8d1ba939a11ea0fa5607e1bc6e693ec318bdb097aedd042d76e695dcebebd142e2e4ac30b1905dff03ec36d9cc70577e4dbe5e9ed7c20c7afb13a7f0155f203c6b83b9f1ad3d20a0d4aef0fbbbcf466ffc1bcd482bc2f5e0"),
                Bytes.fromHexString(
                    "8cc1795de015f2b0e72116f169f3b4624b7738ceebea354e0bd9051c27b86f647ea36cad57ea6884c1a8adf9b45cd83514fa687e68878bbd613d793aa10986d5a0411f081689229e0d72133b3667b9f3f1a02211d0e680564eb1ea43393e1f36"),
                Bytes.fromHexString(
                    "aa9281c61113c343a108de1036570feefc72fb7a96ff11f73024de12b83f29631f5a8a5900e6f10b15227c6f7462881511271bf785ebdf95ce288100e5dab391f664f6ff76c72b65b34479a4f43e5e8eba292209d6654157286ad3242ac342db"),
                Bytes.fromHexString(
                    "aaf16866275082e59d415db317aa874267d048ee405a553e852e6d175711d31a1fee99912345915bce121f43bc3e00d81338e5fcd3c8a1012fb4f172a9fe15622dd368b4d9d5cb60d189f423b071791fe26cea7676aca8df07965cacf80b0cd0"),
                Bytes.fromHexString(
                    "accc80b3d8a6ffa648487a3d3c0ce1aeeb5401edf3cf2e385ea4a6d5fc110054fcce38f01f1da7141bbed30eb7a0a6810c82212bbb9da75d6033082dbcf6bc6a5791f85aa0f045a10da5de015edbf369b4d23b32b0c058962d2ee88e6911f994"),
                Bytes.fromHexString(
                    "83f1089395a16077738cc7c9a6d6a3dc9033aac4abc508af5a1f007ca92e1a80b2e6f2dbda7fdcf0d5646de790a6201d0a9cfbcb6620a1426600e3a6a425ec004384f49fb9dcd166691a47177d45dcbcb761a11d46220b0aa09fc946131f7aa5"),
                Bytes.fromHexString(
                    "9246bb586d43cb817c2e15ed609156e9f1cd284ba2f4797bbfa51c0341e1ba382eaac059aa9f63fb88d228a1a932839a171e7c7d00199dc7c4d6c5ea038a02cbc3cc5297c70401520e70ebbcffacd6a703f62896f3c788f94dde3c33ab0ecbdb"),
                Bytes.fromHexString(
                    "a316cb7c74feb0563c56cc79015e2774fbeca458bf8e9fb07894f9d6bcd73f7fb9428e87c816e5629e4bf7f3ec567fbc091549471b75492dde08217cb334b716b4582b24384586e53388873a78a90ec01bd7c3bace9cfc52161467df16e27c33"),
                Bytes.fromHexString(
                    "ade18c74bbe60d1d69f4a570f8e5fd8696c26cc9e02829040b6b14cb9c49a4b3263b5bd5e16ec0b29010b4be054c16ab09304e23442af7d7f5fcc60bc6c5634ab6e4aed7ef334b2785e4c7672d59a687278e42d310342db5e5975d716e6d1595"),
                Bytes.fromHexString(
                    "b7728800bb2039acf228fa3d8028569c426cb85d28b2b5820bbef938d5ca8c4df981d3e01a309e26ca101e8295d0f6990c03b8c239798323575874a4ee5bfe46cfe99b9657189142aacd8f8d1f26cf4c0e73c6397c31ba8f18102b9ea315b638"),
                Bytes.fromHexString(
                    "8fb14f2a9be193f54977ecd3021663108ea143627b9a9d9faff85d1a86b855f6c437eab435fad3304f245bd7732af07f1173494cdb802fb96e85d2db89e1643206e183f3b228ca8d3f586e71aa9308eaf0223100bf07942fc39e465016d1f775"),
                Bytes.fromHexString(
                    "ac1e025e53d98fdb3380489dce82d9d4bd3a6c98f0a523b841cb09a6f26ddd4d22efd98776e78d10fd996995fd00e81e08d3c25dd14a54b25a9d483677a24bbb8d1cb41a443b2c71038e6893b1b30f70758424e0f2039a48060191389033ef55"),
                Bytes.fromHexString(
                    "a4c017311b9e930868132527a9849072b91db04fd36c619ae39c98da9e2174e6201d3c2ff1246c06b1b6815bbf3ea4a1116564f55ee2fe4c4d655e2294c0ded842cba209c255ca3d7b7f82d162f97890dfdeed087aa2f87cbfc61d61815da39d"),
                Bytes.fromHexString(
                    "89516315a3956b455843c2555248bd94dcb19993060fe75fdd51f7aa9c9147ab13997d8a98036a8f04bee5c91d78d2990907e35a52537a8ab3ed15f1a71afdcd38044a5b6e93f662b9d36c16933a881927cacae668c4c06ee6f004c9e3989bad"),
                Bytes.fromHexString(
                    "a1e78a011e210400c68ca76045f7da74119bff3cbe382efd2bd2ac76567c52d68d75536a91999d084043e1ce2d07d02e0b69fb99924101d2543521747536fbc51b0454aa9a4cbbec101121f597863a5c0fee2ca5eab35dff9b9085bef8b2b0d0"),
                Bytes.fromHexString(
                    "830fd8d083e39153ecab43cabb22e29d7b44a55fba467af4ddd3f069439d2972ef53c3518de788f96b3f4f64963987d0155ba27afc28643af3de8e476ff515a68285728167408f45d99e574680bda6bacdd4322e587e4aa99386e035c0e931ad"),
                Bytes.fromHexString(
                    "b89584da22237e3061d991b1a55a5e55dc637b8b671130d304587729348138ef87885180310efe9f9f6d3580b9d7fdcf0649e8a79d2dec8c25a9f53df0fac5d517db999029cbfdd7c2cbd3e9a5503e5d267d3d8ad752335915c92b850b14bafb"),
                Bytes.fromHexString(
                    "959b8030733799882c5e3735479924b013756e57b893f9792bab4043e2d362d77cf308166d782e3989caa771b8a0c0a01302cb7b5e8ca12e2d6cebd59d4cd173c9dc25f438bac597fab17b4ff44997a489c168e7204b7d7c21d0938f0a2e3b51"),
                Bytes.fromHexString(
                    "a0a9e5503d9afe0027891dab890c687fd5f5fac5741418490c64d7c15f59533dd603a50163c79402afa61bd02de486761983c94501da17e6bbe78c497f2122210071602f578adc0ebe7a4679f87fe77e09c8c122de69105f13455fea25f08e6f"),
                Bytes.fromHexString(
                    "9811487283ad620cd7c9b303ae2f348d0e6f5ee17b504baaa817ae207adb912a00d3cc36dbf48745eb899e6b6e22f09f0f9ba29d949ecd7350fbbfe87a8c7cdd5d0e687fc807751d07634aaf7c38baf3b24a0670c38fa6ccd7431436fc95525f"),
                Bytes.fromHexString(
                    "8a13aa5071c526e560def7d8583393942f07d88c9d8d26c98738fd65f57af2e3326dbb1edff0f39fe98eda4a13ed4fd71844254b954690154c4804e1c4a53df9dc4643f4b7b09d0860070f6b2318d0d63d28fb56bf5b6ff456a18dfc72fdfbbe"),
                Bytes.fromHexString(
                    "b9c90ff6bff5dd97d90aee27ea1c61c1afe64b054c258b097709561fe00710e9e616773fc4bdedcbf91fbd1a6cf139bf14d20db07297418694c12c6c9b801638eeb537cb3741584a686d69532e3b6c12d8a376837f712032421987f1e770c258"))));
    final int numberOfBlobs = 4;
    final List<Bytes> blobs = getSampleBlobs(numberOfBlobs);
    final List<KZGCommitment> kzgCommitments =
        blobs.stream().map(kzg::blobToKzgCommitment).collect(Collectors.toList());
    final List<KZGProof> kzgProofs =
        Streams.zip(
                kzgCommitments.stream(),
                blobs.stream(),
                (kzgCommitment, blob) -> kzg.computeBlobKzgProof(blob, kzgCommitment))
            .collect(Collectors.toList());

    assertThat(kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, kzgProofs)).isTrue();

    assertThat(
            kzg.verifyBlobKzgProofBatch(getSampleBlobs(numberOfBlobs), kzgCommitments, kzgProofs))
        .isFalse();
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, getSampleCommitments(numberOfBlobs), kzgProofs))
        .isFalse();
    final List<KZGProof> invalidProofs =
        getSampleBlobs(numberOfBlobs).stream()
            .map((Bytes blob) -> kzg.computeBlobKzgProof(blob, kzg.blobToKzgCommitment(blob)))
            .collect(Collectors.toList());
    assertThat(kzg.verifyBlobKzgProofBatch(blobs, kzgCommitments, invalidProofs)).isFalse();
  }

  private void loadTrustedSetup() {
    final String trustedSetup =
        Resources.getResource(TrustedSetups.class, TRUSTED_SETUP_PATH).toExternalForm();
    kzg.loadTrustedSetup(trustedSetup);
  }

  private List<Bytes> getSampleBlobs(final int count) {
    return IntStream.range(0, count).mapToObj(__ -> getSampleBlob()).collect(Collectors.toList());
  }

  private Bytes getSampleBlob() {
    return IntStream.range(0, FIELD_ELEMENTS_PER_BLOB)
        .mapToObj(__ -> randomBLSFieldElement())
        .map(fieldElement -> Bytes.wrap(fieldElement.toArray(ByteOrder.BIG_ENDIAN)))
        .reduce(Bytes::wrap)
        .orElse(Bytes.EMPTY);
  }

  private List<KZGCommitment> getSampleCommitments(final int count) {
    return IntStream.range(0, count)
        .mapToObj(__ -> getSampleCommitment())
        .collect(Collectors.toList());
  }

  private KZGCommitment getSampleCommitment() {
    return kzg.blobToKzgCommitment(getSampleBlob());
  }

  private UInt256 randomBLSFieldElement() {
    while (true) {
      final BigInteger attempt = new BigInteger(BLS_MODULUS.bitLength(), RND);
      if (attempt.compareTo(BLS_MODULUS) < 0) {
        return UInt256.valueOf(attempt);
      }
    }
  }
}
