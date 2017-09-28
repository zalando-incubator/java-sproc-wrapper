package de.zalando.sprocwrapper.example;

import com.google.common.base.Optional;

import de.zalando.typemapper.annotations.DatabaseField;
import de.zalando.typemapper.annotations.DatabaseType;

/**
 * @author  pribeiro
 */
@DatabaseType(inheritance = true)
public class GuavaOptionalLookupType extends LookupType {

    @DatabaseField
    public Optional<Integer> c = Optional.absent();

    public GuavaOptionalLookupType() { }
}
