package fr.cnrs.lacito.fieldarchive.config;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
//cette classe fournit l’infrastructure de stockage RDF

@Configuration
public class Rdf4jConfig {

    //    @Bean
//    public Repository repository() {
//        SailRepository repo = new SailRepository(new MemoryStore());
//        repo.init();
//        return repo;
//    }

    @Bean
    public Repository repository() {
        // Use NativeStore instead of MemoryStore
        NativeStore sail = new NativeStore(new File("./data/rdf-store"));

        SailRepository repo = new SailRepository(sail);
        repo.init();
        return repo;
    }
}
