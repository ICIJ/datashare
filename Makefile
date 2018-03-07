run:
		java -cp "datashare-dist/target/datashare-dist-0.7-all/lib/*" org.icij.datashare.cli.DatashareCli

clean:
		mvn clean

dist:
		mvn package

install:
		mvn install

unit:
		mvn test
