package paymenttransferservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import paymenttransferservice.domain.Account;
import paymenttransferservice.domain.User;
import paymenttransferservice.repository.AccountRepository;
import paymenttransferservice.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataSeeder implements CommandLineRunner {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        Account jane = seedAccount("Jane", "Doe", "jane_doe", "jane_doe@example.com", new BigDecimal("1000.00"));
        Account john = seedAccount("John", "Doe", "john_doe", "john_doe@example.com", new BigDecimal("500.00"));

        log.info("Seeded demo data: Jane's account={} (balance {} {}), John's account={} (balance {} {})",
                jane.getId(), jane.getBalance(), jane.getCurrency(),
                john.getId(), john.getBalance(), john.getCurrency());
    }

    private Account seedAccount(String firstName, String lastName, String username, String email, BigDecimal balance) {
        User user = new User();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUsername(username);
        user.setEmail(email);
        userRepository.save(user);

        Account account = new Account();
        account.setOwner(user);
        account.setBalance(balance);
        account.setCurrency(EUR);
        return accountRepository.save(account);
    }
}
