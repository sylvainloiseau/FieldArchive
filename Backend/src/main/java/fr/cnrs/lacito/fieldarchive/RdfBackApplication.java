package fr.cnrs.lacito.fieldarchive;

import fr.cnrs.lacito.fieldarchive.example.Example;
import org.eclipse.rdf4j.spring.RDF4JConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;



@SpringBootApplication
@Import(RDF4JConfig.class)
public class RdfBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(RdfBackApplication.class, args);
        Example.example();
    }

}
