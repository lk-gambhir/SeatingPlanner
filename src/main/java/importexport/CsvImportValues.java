package importexport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Stores CSV values as quoted CSV text so commas and quotes remain readable. */
public final class CsvImportValues {
    private CsvImportValues() {}

    public static String encodeHeaders(Map<String, Integer> headerMap) {
        String[] names = new String[headerMap.size()];
        headerMap.forEach((name, index) -> names[index] = name);
        return encodeRecord(Arrays.asList(names));
    }

    public static String encodeRecord(List<String> values) {
        StringWriter output = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            printer.printRecord(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode CSV import values", exception);
        }
        return withoutLineEnding(output.toString());
    }

    public static String encodeTable(List<String> headers, List<String> values) {
        StringWriter output = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            printer.printRecord(headers);
            printer.printRecord(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode normalized import values", exception);
        }
        return withoutLineEnding(output.toString());
    }

    private static String withoutLineEnding(String value) {
        return value.endsWith("\r\n") ? value.substring(0, value.length() - 2) : value;
    }
}
