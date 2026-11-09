package cl.edu.integracion.inventario;

import cl.edu.integracion.inventario.model.Inventario;
import cl.edu.integracion.inventario.repository.InventarioRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class InventarioApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventarioApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(InventarioRepository repo) {
        return args -> {
            if (repo.count() == 0) {
                repo.saveAll(List.of(
                        new Inventario("PROD-001", 100),
                        new Inventario("PROD-002", 50),
                        new Inventario("PROD-003", 10)));
            }
        };
    }
}
