package otto.nflverse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A streaming reader for the CSV releases nflverse and DynastyProcess
 * publish. The season files run to tens of megabytes, so rows arrive one
 * at a time and nothing holds the whole body in memory.
 *
 * Rows are addressed by column name, not by position: these files gain
 * and reorder columns between seasons, and a name-keyed read survives
 * that. A row missing a column reads as blank rather than throwing, so
 * one drifted column never costs the whole feed.
 */
final class Csv {

    private static final int BUFFER = 1 << 16;

    private Csv() {
    }

    /**
     * One row, addressed by column name.
     *
     * @param columns column name to index, shared across every row
     * @param values this row's fields
     */
    record Row(Map<String, Integer> columns, List<String> values) {

        String text(String column) {
            Integer index = columns.get(column);
            if (index == null || index >= values.size()) {
                return "";
            }
            return values.get(index);
        }

        /** A blank or unparsable field counts as zero, as these files write "NA". */
        double number(String column) {
            String value = text(column);
            if (value.isBlank()) {
                return 0.0;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        int integer(String column) {
            return (int) number(column);
        }
    }

    /**
     * Streams the rows of a CSV body. The stream reads lazily from the
     * given input stream, so the caller must consume it before the
     * underlying connection closes.
     */
    static Stream<Row> stream(InputStream in) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), BUFFER);
        String header = nextRecord(reader);
        if (header == null) {
            return Stream.empty();
        }
        Map<String, Integer> columns = index(split(header));
        Iterator<Row> rows = new Iterator<>() {

            private String pending = nextRecord(reader);

            @Override
            public boolean hasNext() {
                return pending != null;
            }

            @Override
            public Row next() {
                if (pending == null) {
                    throw new NoSuchElementException();
                }
                Row row = new Row(columns, split(pending));
                pending = nextRecord(reader);
                return row;
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(rows, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    private static Map<String, Integer> index(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int position = 0; position < header.size(); position++) {
            columns.putIfAbsent(header.get(position), position);
        }
        return Map.copyOf(columns);
    }

    /**
     * One CSV record, which may span several lines: a quoted field is
     * allowed to contain a newline, so a record ends only where the
     * quotes balance.
     */
    private static String nextRecord(BufferedReader reader) {
        try {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            StringBuilder record = new StringBuilder(line);
            while (unbalancedQuotes(record)) {
                String continuation = reader.readLine();
                if (continuation == null) {
                    break;
                }
                record.append('\n').append(continuation);
            }
            return record.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean unbalancedQuotes(CharSequence record) {
        int quotes = 0;
        for (int position = 0; position < record.length(); position++) {
            if (record.charAt(position) == '"') {
                quotes++;
            }
        }
        return quotes % 2 != 0;
    }

    /** Splits one record into fields, honoring quotes and doubled quotes. */
    private static List<String> split(String record) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int position = 0; position < record.length(); position++) {
            char character = record.charAt(position);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (position + 1 < record.length() && record.charAt(position + 1) == '"') {
                    field.append('"');
                    position++;
                } else {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
