/**
 * JPMS module boundary for Maven Central consumers.
 *
 * <p>
 * See {@code designs/public_api.md} for the stability contract. Package
 * {@code me.tibetty.sigbrute.decode.infer} is intentionally not exported.
 */
module me.tibetty.sigbrute {
    requires org.yaml.snakeyaml;

    exports me.tibetty.sigbrute.model;
    exports me.tibetty.sigbrute.parser;
    exports me.tibetty.sigbrute.search;
    exports me.tibetty.sigbrute.decode;
    exports me.tibetty.sigbrute.decode.emit;
    exports me.tibetty.sigbrute.expander;
    exports me.tibetty.sigbrute.util;
}
