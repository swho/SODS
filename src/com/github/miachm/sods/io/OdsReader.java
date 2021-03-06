package com.github.miachm.sods.io;

import com.github.miachm.sods.exceptions.NotAnOds;
import com.github.miachm.sods.exceptions.OperationNotSupported;
import com.github.miachm.sods.spreadsheet.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Internal class for read ODS files
 */
public class OdsReader {
    private static final String CORRECT_MIMETYPE = "application/vnd.oasis.opendocument.spreadsheet";
    private static final String MANIFEST_PATH = "META-INF/manifest.xml";
    private static final Locale defaultLocal = Locale.US;
    private String main_path;
    private SpreadSheet spread;
    private Map<String,byte[]> files;
    private Map<String,Style> styles = new HashMap<>();
    private Map<Integer,Style> rows_styles = new HashMap<>();
    private Map<Integer,Style> columns_styles = new HashMap<>();

    private OdsReader(InputStream in,SpreadSheet spread) throws IOException {
        /* TODO This code if for ods files in zip. But we could have XML-ONLY FILES */
        this.spread = spread;
        styles.put("Default", new Style());
        Uncompressor uncompressor = new Uncompressor(in);
        files = uncompressor.getFiles();

        checkMimeType(files);

        byte[] manifest = getManifest(files);
        readManifest(manifest);
        readContent();
    }

    static public void load(InputStream in,SpreadSheet spread) throws IOException {
        OdsReader reader = new OdsReader(in,spread);
    }

    private void checkMimeType(Map<String,byte[]> map){
        byte[] mimetype = map.get("mimetype");
        if (mimetype == null)
            throw new NotAnOds("This file doesn't contain a mimetype");

        String mimetype_string = new String(mimetype);
        if (!mimetype_string.equals(CORRECT_MIMETYPE))
            throw new NotAnOds("This file doesn't look like an ODS file. Mimetype: " + mimetype_string);
    }

    private byte[] getManifest(Map<String,byte[]> map){
        byte[] manifest = map.get(MANIFEST_PATH);
        if (manifest == null) {
            throw new NotAnOds("Error loading, it doesn't like an ODS file");
        }

        return manifest;
    }

    private void readManifest(byte[] manifest) {
        try{
            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(manifest));

            Element root = doc.getDocumentElement();
            if (!root.getNodeName().equals("manifest:manifest")) {
                throw new NotAnOds("The signature of the manifest is not valid. Is it an ODS file?");
            }

            NodeList files = doc.getElementsByTagName("manifest:file-entry");
            iterateFilesEntryManifest(files);

        }catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
    }

    private void iterateFilesEntryManifest(NodeList files){
        for (int i = 0;i < files.getLength();i++) {
            NamedNodeMap children = files.item(i).getAttributes();
            boolean main_path = false;
            String path = null;
            for (int j = 0;j < children.getLength();j++) {
                Node child = children.item(j);
                if (child.getNodeName().equals("manifest:encryption-data")) {
                    throw new OperationNotSupported("This file has encription technology that it's not supported" +
                            "by this library");
                }
                else if (child.getNodeName().equals("manifest:full-path")){
                    path = child.getNodeValue();
                }
                else if (child.getNodeName().equals("manifest:media-type")){
                    main_path = (child.getNodeValue().equals(CORRECT_MIMETYPE));
                }
            }

            if (main_path)
                this.main_path = path;
        }
    }

    private void readContent() {
        Set<String> names = files.keySet();

        for (String name : names){
            if (name.endsWith(".xml"))
                processContent(files.get(name));
        }
    }

    private void processContent(byte[] bytes) {
        try{
            if (bytes.length == 0)
                return;

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(bytes));

            Element root = doc.getDocumentElement();

            NodeList styles = doc.getElementsByTagName("office:automatic-styles");

            if (styles != null) {
                Node n = styles.item(0);
                if (n != null)
                    iterateStyleEntries(n.getChildNodes());
            }

            NodeList files = doc.getElementsByTagName("office:body");

            if (files != null) {
                Node n = files.item(0);
                if (n != null)
                    iterateFilesEntries(n.getChildNodes());
            }

        }catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
    }

    private void iterateStyleEntries(NodeList childNodes) {
        if (childNodes == null)
            return;
        for (int i = 0;i < childNodes.getLength();i++) {
            Node node = childNodes.item(i);
            if (node.getNodeName().equals("style:style")) {
                NamedNodeMap map = node.getAttributes();
                Node aux = map.getNamedItem("style:name");
                if (aux != null) {
                    Style style = readStyleEntry(node.getChildNodes());
                    styles.put(aux.getNodeValue(),style);
                }
            }
        }
    }

    private Style readStyleEntry(NodeList childNodes) {
        Style style = new Style();
        for (int i = 0;i < childNodes.getLength();i++) {
            Node n = childNodes.item(i);
            if (n.getNodeName().equals("style:text-properties")) {
                NamedNodeMap map = n.getAttributes();
                Node bold = map.getNamedItem("fo:font-weight");
                if (bold != null) {
                    style.setBold(bold.getNodeValue().equals("bold"));
                }
                Node italic = map.getNamedItem("fo:font-style");
                if (italic != null) {
                    style.setItalic(italic.getNodeValue().equals("italic"));
                }
                Node underline = map.getNamedItem("style:text-underline-style");
                if (underline != null) {
                    style.setUnderline(underline.getNodeValue().equals("solid"));
                }
                Node fontcolor = map.getNamedItem("fo:color");
                if (fontcolor != null) {
                    style.setFontColor(new Color(fontcolor.getNodeValue()));
                }
                Node fontsize = map.getNamedItem("fo:font-size");
                if (fontsize != null) {
                    String nodeValue = fontsize.getNodeValue();
                    if (nodeValue.endsWith("pt")) {
                        int index = nodeValue.lastIndexOf("pt");
                        int fontSize = Integer.parseInt(nodeValue.substring(0,index));
                        style.setFontSize(fontSize);
                    }
                    else
                        throw new OperationNotSupported("Error, font size is not measured in PT. Skipping...");
                }
            }
            if (n.getNodeName().equals("style:table-cell-properties")) {
                NamedNodeMap map = n.getAttributes();

                Node backgroundColor = map.getNamedItem("fo:background-color");
                if (backgroundColor != null) {
                    style.setBackgroundColor(new Color(backgroundColor.getNodeValue()));
                }
            }
        }
        return style;
    }

    private void iterateFilesEntries(NodeList files) {
        if (files == null)
            return;
        for (int i = 0;i < files.getLength();i++){
            Node node = files.item(i);
            if (node.getNodeName().equals("office:spreadsheet")){
                processSpreadsheet(node.getChildNodes());
            }
        }
    }

    private void processSpreadsheet(NodeList list) {
        for (int i = 0;i < list.getLength();i++){
            Node node = list.item(i);
            if (node.getNodeName().equals("table:table")){
                processTable(node);
            }
        }
    }

    private void processTable(Node node){
        NamedNodeMap atributes = node.getAttributes();
        String name = atributes.getNamedItem("table:name").getNodeValue();
        Sheet sheet = new Sheet(name);
        sheet.deleteRow(0);
        sheet.deleteColumn(0);

        NodeList new_list = node.getChildNodes();
        for (int i = 0;i < new_list.getLength();i++){
            Node n = new_list.item(i);
            Style style = styles.get(getAtribFromNode(n,"table:default-cell-style-name",null));
            if (n.getNodeName().equals("table:table-column")) {
                int numColumns = getNumberOfColumns(n);

                if (style != null)
                    for (int j = sheet.getMaxColumns();j < sheet.getMaxColumns()+numColumns;j++)
                        columns_styles.put(j,style);

                sheet.appendColumns(numColumns);
            }
            else if (n.getNodeName().equals("table:table-row")) {
                if (style != null)
                    rows_styles.put(sheet.getMaxRows(),style);
                sheet.appendRow();
                processCells(n.getChildNodes(),sheet);
            }
        }
        spread.appendSheet(sheet);
    }

    private int getNumberOfColumns(Node n){
        Node n5 = n.getAttributes().getNamedItem("table:number-columns-repeated");
        int incr = 1;
        if (n5 != null)
            incr = Integer.parseInt(n5.getNodeValue());
        return incr;
    }

    private void processCells(NodeList childNodes,Sheet sheet) {
        int column = 0;
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node n = childNodes.item(i);

            // number of columns repeated
            long number_columns_repeated = 0;
            // value and style to be copied
            Object last_cell_value = null;
            Style last_style = null;

            if (n.getNodeName().equals("table:table-cell")) {
                Range range = sheet.getRange(sheet.getMaxRows() - 1, column);
                String valueType = getValueType(n);
                String formula = getFormula(n);
                Object value = null;

                Node valueNode = n.getAttributes().getNamedItem("office:value");
                if (valueNode != null) {
                    value = getValue(valueNode.getNodeValue(), valueType);
                }

                number_columns_repeated = Long.parseLong(getAtribFromNode(n, "table:number-columns-repeated", "0").toString());
                
                range.setFormula(formula);
                Style style = styles.get(getStyle(n));
                
                if (style == null) {
                    style = columns_styles.get(column);
                }
                if (style == null) {
                    style = rows_styles.get(sheet.getMaxRows()-1);
                }
                if (style != null) {
                    range.setStyle(style);
                }
                
                last_style = style;

                if (value == null) {
                    NodeList cells = n.getChildNodes();
                    for (int j = 0; j < cells.getLength(); j++) {
                        Node cell = cells.item(j);
                        String cell_name = cell.getNodeName();
                        if (cell_name.equals("text:p")) {
                            // TODO : Iterate over the children
                            value = getValue(cell.getTextContent(), valueType);
                        }
                    }
                }
                last_cell_value = value;
                range.setValue(value);
                column++;
            }

            if(number_columns_repeated > 0) {
            	for(int j = 0; j < number_columns_repeated-1; j++) {
            		Range range = sheet.getRange(sheet.getMaxRows() - 1, column);
            		if(last_style != null) {
            			range.setStyle(last_style);
            		}

            		range.setValue(last_cell_value);
            		column++;
            	}
            }
	}
    }

    private String getValueType(Node n) {
        return getAtribFromNode(n,"office:value-type","string");
    }

    private String getFormula(Node n) {
        return getAtribFromNode(n,"table:formula",null);
    }

    private String getStyle(Node n) {
        return getAtribFromNode(n,"table:style-name",null);
    }

    private String getAtribFromNode(Node n, String atrib, String defaultValue) {
        Node type = n.getAttributes().getNamedItem(atrib);
        if (type !=  null) {
            defaultValue = type.getNodeValue();
        }
        return defaultValue;
    }

    private Object getValue(String value, String valueType) {
        try {
            NumberFormat format = NumberFormat.getInstance(defaultLocal);
            if (valueType.equals("integer")) {
                return format.parse(value).longValue();
            }
            else if (valueType.equals("float")) {
                return format.parse(value).doubleValue();
            }
            else
                return value;
        }
        catch (ParseException e) {
            return value;
        }
    }

}
