package me.tibetty.sigbrute.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import me.tibetty.sigbrute.model.ArgSpec;
import me.tibetty.sigbrute.model.LeafArgSpec;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.model.TupleArgSpec;
import me.tibetty.sigbrute.parser.YamlConfigParser;
import me.tibetty.sigbrute.util.Keccak256Util;
import org.junit.jupiter.api.Test;

/** Complex signature cases sourced from 4byte.directory, plus nested-tuple synthesis. */
class SearchEngineComplexSignatureTest {

    // 4byte.directory —
    // bulkAddRecipients(bytes32[],address[],(string,string,string,string,string)[])
    private static final String BULK_ADD_RECIPIENTS = "bulkAddRecipients(bytes32[],address[],(string,string,string,string,string)[])";
    private static final byte[] BULK_ADD_RECIPIENTS_SELECTOR = hexSelector("d727784a");

    // 4byte.directory —
    // addFlowRecipient(bytes32,(string,string,string,string,string),address,address,address[])
    private static final String ADD_FLOW_RECIPIENT = "addFlowRecipient(bytes32,(string,string,string,string,string),address,address,address[])";
    private static final byte[] ADD_FLOW_RECIPIENT_SELECTOR = hexSelector("f1056538");

    // 4byte.directory — initialize(..., (uint32,uint32,uint32), (string×5), address, address[])
    private static final String SUPERFLUID_INITIALIZE = "initialize(address,address,address,address,address,address,address,"
        + "(uint32,uint32,uint32),(string,string,string,string,string),address,address[])";
    private static final byte[] SUPERFLUID_INITIALIZE_SELECTOR = hexSelector("73a4859c");

    // Synthetic — tuple-of-tuple nested inside a top-level tuple
    private static final String NESTED_TUPLE_ROUTING = "setRouting((address,(uint32,uint8)),bytes32)";

    @Test
    void fourByteSelectorsMatchKeccak() {
        assertTrue(
            Keccak256Util.selectorMatches(BULK_ADD_RECIPIENTS, BULK_ADD_RECIPIENTS_SELECTOR));
        assertTrue(Keccak256Util.selectorMatches(ADD_FLOW_RECIPIENT, ADD_FLOW_RECIPIENT_SELECTOR));
        assertTrue(
            Keccak256Util.selectorMatches(SUPERFLUID_INITIALIZE, SUPERFLUID_INITIALIZE_SELECTOR));
    }

    @Test
    void findsBulkAddRecipientsFrom4byte() {
        var config = new SearchConfig(BULK_ADD_RECIPIENTS_SELECTOR,
            List.of("bulkAddRecipients", "bulkRemoveRecipients"),
            List.of(new LeafArgSpec(List.of("bytes32[]")), new LeafArgSpec(List.of("address[]")),
                metadataTupleArray()),
            1, true);

        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();

        assertEquals(1, results.size());
        assertEquals(BULK_ADD_RECIPIENTS, results.get(0));
    }

    @Test
    void findsAddFlowRecipientFrom4byte() {
        var config = new SearchConfig(ADD_FLOW_RECIPIENT_SELECTOR,
            List.of("addFlowRecipient"),
            List.of(new LeafArgSpec(List.of("bytes32")), metadataTuple(),
                new LeafArgSpec(List.of("address")), new LeafArgSpec(List.of("address")),
                new LeafArgSpec(List.of("address[]"))),
            1, true);

        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();

        assertEquals(1, results.size());
        assertEquals(ADD_FLOW_RECIPIENT, results.get(0));
    }

    @Test
    void findsSuperfluidInitializeFrom4byte() {
        var args = new ArrayList<ArgSpec>();
        IntStream.range(0, 7).forEach(i -> args.add(new LeafArgSpec(List.of("address"))));
        args.add(new TupleArgSpec(List.of(new LeafArgSpec(List.of("uint32")),
            new LeafArgSpec(List.of("uint32")), new LeafArgSpec(List.of("uint32")))));
        args.add(metadataTuple());
        args.add(new LeafArgSpec(List.of("address")));
        args.add(new LeafArgSpec(List.of("address[]")));

        var config = new SearchConfig(SUPERFLUID_INITIALIZE_SELECTOR,
            List.of("initialize"), args, 1, true);

        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();

        assertEquals(1, results.size());
        assertEquals(SUPERFLUID_INITIALIZE, results.get(0));
    }

    @Test
    void findsNestedTupleOfTuple() {
        var selector = selectorBytes(NESTED_TUPLE_ROUTING);
        var config = new SearchConfig(selector, List.of("setRouting"),
            List.of(new TupleArgSpec(List.of(new LeafArgSpec(List.of("address")),
                new TupleArgSpec(List.of(new LeafArgSpec(List.of("uint32")),
                    new LeafArgSpec(List.of("uint8")))))),
                new LeafArgSpec(List.of("bytes32"))),
            1, true);

        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();

        assertEquals(1, results.size());
        assertEquals(NESTED_TUPLE_ROUTING, results.get(0));
    }

    @Test
    void parsesAndFindsBulkAddRecipientsYamlExample() throws Exception {
        try (InputStream in = getClass()
            .getResourceAsStream("/examples/config/superfluid_bulk_add_recipients.yaml")) {
            var config = new YamlConfigParser().parse(in);

            assertEquals(BULK_ADD_RECIPIENTS_SELECTOR.length, config.selector().length);
            for (int i = 0; i < 4; i++) {
                assertEquals(BULK_ADD_RECIPIENTS_SELECTOR[i], config.selector()[i]);
            }

            assertEquals(3, config.args().size());
            assertInstanceOf(TupleArgSpec.class, config.args().get(2));
            TupleArgSpec tuple = (TupleArgSpec) config.args().get(2);
            assertEquals("[]", tuple.arraySuffix());
            assertEquals(5, tuple.fields().size());

            var results = new SearchEngine(config,
                new PrintStream(OutputStream.nullOutputStream())).search();
            assertEquals(1, results.size());
            assertEquals(BULK_ADD_RECIPIENTS, results.get(0));
        }
    }

    @Test
    void parsesSuperfluidInitializeYamlWithTwoTuples() throws Exception {
        try (InputStream in = getClass()
            .getResourceAsStream("/examples/config/superfluid_gda_initialize.yaml")) {
            var config = new YamlConfigParser().parse(in);

            assertEquals(11, config.args().size());
            assertInstanceOf(TupleArgSpec.class, config.args().get(7));
            assertInstanceOf(TupleArgSpec.class, config.args().get(8));

            TupleArgSpec uintTriplet = (TupleArgSpec) config.args().get(7);
            TupleArgSpec stringQuintet = (TupleArgSpec) config.args().get(8);
            assertEquals(3, uintTriplet.fields().size());
            assertEquals(5, stringQuintet.fields().size());
            assertEquals("", uintTriplet.arraySuffix());
            assertEquals("", stringQuintet.arraySuffix());
            // Exact patterns are not asserted here — they belong to the example file and change
            // independently. Structure (arg count, tuple positions, field counts) is what matters.
        }
    }

    @Test
    void tupleExpansionCoversNestedAndArrayForms() {
        TupleArgSpec nested = new TupleArgSpec(
            List.of(new LeafArgSpec(List.of("address")), new TupleArgSpec(
                List.of(new LeafArgSpec(List.of("uint32")), new LeafArgSpec(List.of("uint8"))))));
        assertEquals(List.of("(address,(uint32,uint8))"), nested.expand().stream().toList());

        assertEquals(List.of("(string,string,string,string,string)[]"),
            metadataTupleArray().expand().stream().toList());
    }

    private static TupleArgSpec metadataTuple() {
        return new TupleArgSpec(List.of(new LeafArgSpec(List.of("string")),
            new LeafArgSpec(List.of("string")), new LeafArgSpec(List.of("string")),
            new LeafArgSpec(List.of("string")), new LeafArgSpec(List.of("string"))));
    }

    private static TupleArgSpec metadataTupleArray() {
        return new TupleArgSpec(metadataTuple().fields(), "[]");
    }

    private static byte[] selectorBytes(String sig) {
        var hash = Keccak256Util.hash(sig);
        return new byte[]{hash[0], hash[1], hash[2], hash[3]};
    }

    private static byte[] hexSelector(String hex) {
        var out = new byte[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
