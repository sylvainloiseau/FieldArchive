package fr.cnrs.lacito.fieldarchive.controllers;

import fr.cnrs.lacito.fieldarchive.services.SparqlService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sparql")
public class SparqlController {

    private final SparqlService service;

    public SparqlController(SparqlService service) {
        this.service = service;
    }

    @PostMapping("/select")
    public List<Map<String, String>> select(@RequestBody Map<String, String> body) {
        String query = body.get("query");
//        System.out.println("SPARQL QUERY:\n" + query);
        return service.select(query);
    }

    @PostMapping("/update")
    public void update(@RequestBody Map<String,String> body){
        service.update(body.get("query"));
    }
}
