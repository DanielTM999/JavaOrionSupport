package dtm.ide.coverage;

import dtm.stools.component.panels.editor.code.gutter.layer.LineMarkerLayer;

public class CoverageGutterLayer extends LineMarkerLayer {

    public CoverageGutterLayer() {
        setStripeWidth(CoverageGutter.STRIPE_WIDTH);
        setSide(Side.RIGHT);
        setClickTolerance(CoverageGutter.STRIPE_WIDTH);
    }
}
