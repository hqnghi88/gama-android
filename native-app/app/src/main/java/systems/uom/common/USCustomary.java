package systems.uom.common;

import javax.measure.Unit;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Length;
import tech.units.indriya.unit.TransformedUnit;
import tech.units.indriya.function.MultiplyConverter;
import si.uom.NonSI;
import tech.units.indriya.unit.Units;

public final class USCustomary {
    @SuppressWarnings("unchecked")
    public static final Unit<Angle> GRADE =
        new TransformedUnit<>("grade", "grad", NonSI.DEGREE_ANGLE,
            MultiplyConverter.ofRational(200, 360));

    @SuppressWarnings("unchecked")
    public static final Unit<Length> FOOT =
        new TransformedUnit<>("foot", "ft", Units.METRE,
            MultiplyConverter.ofRational(3048, 10000));

    @SuppressWarnings("unchecked")
    public static final Unit<Length> FOOT_SURVEY =
        new TransformedUnit<>("foot survey", "ft", Units.METRE,
            MultiplyConverter.ofRational(1200, 3937));

    @SuppressWarnings("unchecked")
    public static final Unit<Length> NAUTICAL_MILE =
        new TransformedUnit<>("nautical mile", "nmi", Units.METRE,
            MultiplyConverter.ofRational(1852, 1));

    @SuppressWarnings("unchecked")
    public static final Unit<Length> INCH =
        new TransformedUnit<>("inch", "in", Units.METRE,
            MultiplyConverter.ofRational(254, 10000));
}
