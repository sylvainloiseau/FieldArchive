import csv, re, sys, json
RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
APP="http://cnrs.lacito/app#"
MATCH="https://fr.cnrs.lacito.fr/fieldArchive/"
def iri(s):
    if s.startswith("rdf:"): return RDF+s[4:]
    return s
def lit(v,t):
    v=v.replace('\\','\\\\').replace('"','\\"')
    if t=="date":
        if re.fullmatch(r"\d{4}",v): return f'"{v}"^^<http://www.w3.org/2001/XMLSchema#gYear>'
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}",v): return f'"{v}"^^<http://www.w3.org/2001/XMLSchema#date>'
    if t=="number": return f'"{v}"^^<http://www.w3.org/2001/XMLSchema#integer>'
    return f'"{v}"'
out=["# Generated from 'Data Matching/{external,internal}_datasource.csv' (+ the hand-written source 'archive')."]
decisions=[]
def graph(name, path):
    out.append(f"<urn:datasource:{name}> {{")
    for r in csv.DictReader(open(path, encoding='utf-8')):
        e,p,v,t=r['entity'],iri(r['property']),r['value'],r['typevalue']
        if p.startswith(MATCH):
            decisions.append((name,e,p[len(MATCH):],v)); continue
        o = f"<{iri(v)}>" if t=="iri" else lit(v,t)
        out.append(f"  <{e}> <{p}> {o} .")
    out.append("}")
graph("tropy", sys.argv[1]); graph("gramps", sys.argv[2])
out.append("""<urn:datasource:archive> {
  <http://archive.local/A1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://xmlns.com/foaf/0.1/Person> .
  <http://archive.local/A1> <http://xmlns.com/foaf/0.1/name> "Marie Lefèvre" .
  <http://archive.local/A1> <http://xmlns.com/foaf/0.1/birthday> "1948"^^<http://www.w3.org/2001/XMLSchema#gYear> .
  <http://archive.local/A2> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://www.ica.org/standards/RiC/ontology#Place> .
  <http://archive.local/A2> <https://www.ica.org/standards/RiC/ontology#name> "Lyon" .
}""")
out.append(f"<{APP}/context/metadata> {{")
for name,typ in [("tropy","external"),("gramps","internal"),("archive","external")]:
    ds=f"<{APP}/datasource/{name}>"
    out.append(f'  {ds} <{APP}sourceType> "{typ}" ; <{APP}shortName> "{name}" ; <{APP}graph> <urn:datasource:{name}> .')
out.append("}")
open(sys.argv[3],'w').write("\n".join(out)+"\n")
print(decisions)
