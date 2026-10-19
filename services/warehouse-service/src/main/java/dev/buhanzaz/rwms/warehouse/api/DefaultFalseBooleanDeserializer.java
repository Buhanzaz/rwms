package dev.buhanzaz.rwms.warehouse.api;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidNullException;

/** Supplies false for an omitted additive boolean while rejecting an explicit JSON null. */
public final class DefaultFalseBooleanDeserializer extends ValueDeserializer<Boolean> {
  @Override
  public Boolean deserialize(JsonParser parser, DeserializationContext context) {
    return parser.getBooleanValue();
  }

  @Override
  public Boolean getAbsentValue(DeserializationContext context) {
    return Boolean.FALSE;
  }

  @Override
  public Boolean getNullValue(DeserializationContext context) {
    throw InvalidNullException.from(
        context,
        PropertyName.construct(context.getParser().currentName()),
        context.constructType(Boolean.class));
  }
}
