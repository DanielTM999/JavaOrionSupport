package demo;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Setter
@Builder
@Accessors(chain = true)
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
public class Customer {

    private final String name;
    private final String document;
    private int loyaltyPoints;

    public void register() {
        log.info("cliente registrado: {}", name);
    }
}
