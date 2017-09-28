package de.zalando.sprocwrapper.example;

import de.zalando.typemapper.annotations.DatabaseField;
import de.zalando.typemapper.annotations.DatabaseType;

import java.util.Optional;

@DatabaseType(inheritance = true)
public class OptionalLookupType extends LookupType {

    @DatabaseField
    public Optional<Integer> c = Optional.empty();

    public OptionalLookupType() { }
}
