/**
 * JPMS module boundary for Maven Central consumers.
 *
 * <p>
 * See {@code designs/public_api.md} for the stability contract. Exported decode
 * packages: {@code decode}, {@code decode.strategy}, {@code decode.emit}. Internal:
 * {@code decode.infer}, {@code decode.layout}, {@code decode.abi}, {@code decode.skeleton},
 * {@code lookup} (CLI HTTP preflight only; requires {@code java.net.http}).
 */
module me.tibetty.sigbrute {
    requires java.net.http;
    requires org.yaml.snakeyaml;

    exports me.tibetty.sigbrute.model;
    exports me.tibetty.sigbrute.parser;
    exports me.tibetty.sigbrute.search;
    exports me.tibetty.sigbrute.decode;
    exports me.tibetty.sigbrute.decode.strategy;
    exports me.tibetty.sigbrute.decode.emit;
    exports me.tibetty.sigbrute.expander;
    exports me.tibetty.sigbrute.util;
}
