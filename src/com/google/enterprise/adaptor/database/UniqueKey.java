// Copyright 2014 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.database;

import com.google.common.annotations.VisibleForTesting;
import com.google.enterprise.adaptor.InvalidConfigurationException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Provides adaptor DocId and fills getDocContent query. */
class UniqueKey {
  private static final Logger log
      = Logger.getLogger(UniqueKey.class.getName());

  static enum ColumnType {
    INT,
    STRING,
    TIMESTAMP,
    DATE,
    TIME,
    LONG
  }

  private final List<String> names;  // columns used for DocId
  private final Map<String, ColumnType> types;  // types of DocId columns
  private final List<String> contentSqlCols;  // columns for content query
  private final List<String> aclSqlCols;  // columns for acl query

  UniqueKey(String ukDecls, String contentSqlColumns, String aclSqlColumns) {
    if (null == ukDecls) {
      throw new NullPointerException();
    }
    if (null == contentSqlColumns) {
      throw new NullPointerException();
    }
    if (null == aclSqlColumns) {
      throw new NullPointerException();
    }

    if ("".equals(ukDecls.trim())) {
      throw new InvalidConfigurationException("unique key can't be empty");
    }

    List<String> tmpNames = new ArrayList<String>();
    Map<String, ColumnType> tmpTypes = new TreeMap<String, ColumnType>();
    for (String e : ukDecls.split(",", 0)) {
      log.fine("element: `" + e + "'");
      e = e.trim();
      String def[] = e.split(":", 2);
      if (2 != def.length) {
        throw new InvalidConfigurationException("bad def: `" + e + "'");
      }
      def[0] = def[0].trim();
      def[1] = def[1].trim();
      String name = def[0];
      ColumnType type;
      if ("int".equals(def[1].toLowerCase())) {
        type = ColumnType.INT;
      } else if ("string".equals(def[1].toLowerCase())) {
        type = ColumnType.STRING;
      } else if ("timestamp".equals(def[1].toLowerCase())) {
        type = ColumnType.TIMESTAMP;
      } else if ("date".equals(def[1].toLowerCase())) {
        type = ColumnType.DATE;
      } else if ("time".equals(def[1].toLowerCase())) {
        type = ColumnType.TIME;
      } else if ("long".equals(def[1].toLowerCase())) {
        type = ColumnType.LONG;
      } else {
        throw new InvalidConfigurationException("bad type: `" + def[1] + "'");
      } 
      if (tmpTypes.containsKey(name)) {
        throw new InvalidConfigurationException("name repeat: `" + name + "'");
      }
      tmpNames.add(name);
      tmpTypes.put(name, type);
    }
    names = Collections.unmodifiableList(tmpNames);
    types = Collections.unmodifiableMap(tmpTypes);

    if ("".equals(contentSqlColumns.trim())) {
      contentSqlCols = names;
    } else {
      contentSqlCols = splitIntoNameList(contentSqlColumns, tmpTypes.keySet());
    }

    if ("".equals(aclSqlColumns.trim())) {
      aclSqlCols = names;
    } else {
      aclSqlCols = splitIntoNameList(aclSqlColumns, tmpTypes.keySet());
    }
  }

  private static List<String> splitIntoNameList(String cols,
      Set<String> validNames) {
    List<String> tmpContentCols = new ArrayList<String>();
    for (String name : cols.split(",", 0)) {
      name = name.trim();
      if (!validNames.contains(name)) {
        throw new InvalidConfigurationException("unknown name: `" + name + "'");
      }
      tmpContentCols.add(name); 
    }
    return Collections.unmodifiableList(tmpContentCols);
  }

  @VisibleForTesting
  UniqueKey(String ukDecls) {
    this(ukDecls, "", "");
  }

  String makeUniqueId(ResultSet rs, boolean encode) throws SQLException {
    if (!encode) {
      if (names.size() == 1) {
        return rs.getString(names.get(0)).replaceAll(" ", "%20");
      }
      throw new AssertionError("not encoding implies exactly one parameter");
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      String part;
      switch (types.get(name)) {
        case INT:
          part = "" + rs.getInt(name);
          break;
        case STRING:
          part = rs.getString(name);
          break;
        case TIMESTAMP:
          part = "" + rs.getTimestamp(name).getTime();
          break;
        case DATE:
          part = "" + rs.getDate(name);
          break;
        case TIME:
          part = "" + rs.getTime(name);
          break;
        case LONG:
          part = "" + rs.getLong(name);
          break;
        default:
          throw new AssertionError("invalid type: `" + types.get(name) + "'"); 
      }
      if (encode) {
        part = encodeSlashInData(part);
      }
      sb.append("/").append(part);
    }
    return sb.toString().substring(1).replaceAll(" ", "%20");
  }

  private void setSqlValues(PreparedStatement st, String uniqueId,
      List<String> sqlCols) throws SQLException {
    // parse on / that isn't preceded by escape char _
    // (a / that is preceded by _ is part of column value)
    String parts[] = uniqueId.split("(?<!_)/", -1);
    if (parts.length != names.size()) {
      throw new IllegalStateException(
          "wrong number of values for primary key: "
          + "id: " + uniqueId + ", parts: " + Arrays.asList(parts));
    }
    Map<String, String> zip = new TreeMap<String, String>();
    for (int i = 0; i < parts.length; i++) {
      String columnValue = decodeSlashInData(parts[i]);
      zip.put(names.get(i), columnValue);
    }
    for (int i = 0; i < sqlCols.size(); i++) {
      String colName = sqlCols.get(i);
      ColumnType typeOfCol = types.get(colName);
      String valueOfCol = zip.get(colName);
      switch (typeOfCol) {
        case INT:
          st.setInt(i + 1, Integer.parseInt(valueOfCol));
          break;
        case STRING:
          st.setString(i + 1, valueOfCol);
          break;
        case TIMESTAMP:
          long timestamp = Long.parseLong(valueOfCol);
          st.setTimestamp(i + 1, new java.sql.Timestamp(timestamp));
          break;
        case DATE:
          st.setDate(i + 1, java.sql.Date.valueOf(valueOfCol));
          break;
        case TIME:
          st.setTime(i + 1, java.sql.Time.valueOf(valueOfCol));
          break;
        case LONG:
          st.setLong(i + 1, Long.parseLong(valueOfCol));
          break;
        default:
          throw new AssertionError("invalid type: `" + typeOfCol + "'"); 
      }
    }   
  }

  void setContentSqlValues(PreparedStatement st, String uniqueId)
      throws SQLException {
    setSqlValues(st, uniqueId, contentSqlCols);
  }

  void setAclSqlValues(PreparedStatement st, String uniqueId)
      throws SQLException {
    setSqlValues(st, uniqueId, aclSqlCols);
  }

  @VisibleForTesting
  static String encodeSlashInData(String data) {
    if (-1 == data.indexOf('/') && -1 == data.indexOf('_')) {
      return data;
    }
    char lastChar = data.charAt(data.length() - 1);
    // Don't let data end with _, because then a _ would
    // precede the seperator /.  If column value ends with
    // _ then append a / and take it away when decoding.
    // Since, column value could also end with / that we 
    // wouldn't want taken away during decoding, append a
    // second / when column value ends with / too.

    if ('_' == lastChar || '/' == lastChar) {
      // For _ case:
      // Suppose unique key values are 5_ and 6_. Without this code here
      // the unique keys would be encoded into DocId "5__/6__".  Then when
      // parsing DocId, the slash would not be used as a splitter because
      // it is preceded by _.
      // For / case:
      // Suppose unique key values are 5/ and 6/. Without appending another
      //  /, DocId will be 5_//6_/, which will be split and decoded as 5 
      // and 6.
      data += '/';
    }
    data = data.replace("_", "__");
    data = data.replace("/", "_/");
    return data;
  }

  @VisibleForTesting
  static String decodeSlashInData(String id) {
    // If id value ends with / (encoded as _/) we know that
    // this last / was appended because collumn value ended
    // with either _ or /. We take away this last added / 
    // character.
    if (id.endsWith("_/")) {
      id = id.substring(0, id.length() - 2);
    }
    id = id.replace("_/", "/");
    id = id.replace("__", "_");
    return id;
  }

  /** Number of columns that make up the primary key. */
  @VisibleForTesting
  int numElementsForTest() {
    return names.size(); 
  }

  /** Name of particular column in primary key. */
  @VisibleForTesting
  String nameForTest(int i) {
    return names.get(i);
  }

  /** Type of particular column in primary key. */
  @VisibleForTesting
  ColumnType typeForTest(int i) {
    return types.get(names.get(i));
  }

  @Override
  public String toString() {
    return "UniqueKey(" + names + "," + types + "," + contentSqlCols + ","
        + aclSqlCols + ")";
  }

  @Override
  public boolean equals(Object other) {
    boolean same = false;
    if (other instanceof UniqueKey) {
      UniqueKey key = (UniqueKey) other;
      same = names.equals(key.names)
          && types.equals(key.types)
          && contentSqlCols.equals(key.contentSqlCols)
          && aclSqlCols.equals(key.aclSqlCols);
    }
    return same;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(names, types, contentSqlCols, aclSqlCols);
  }
}
