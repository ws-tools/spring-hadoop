/*
 * Copyright 2011-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.hadoop.hbase;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTableInterfaceFactory;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Central class for accessing the HBase API. Simplifies the use of HBase and helps to avoid common errors. 
 * It executes core HBase workflow, leaving application code to invoke actions and extract results. 
 * 
 * @author Costin Leau
 */
public class HbaseTemplate implements InitializingBean {

	private Charset charset;
	private String encoding;
	private String defaultTable;
	private boolean autoFlush = true;
	private HTableInterfaceFactory tableFactory;
	private Configuration configuration;

	public HbaseTemplate() {
	}

	public HbaseTemplate(Configuration configuration) {
		this.configuration = configuration;
		afterPropertiesSet();
	}

	@Override
	public void afterPropertiesSet() {
		Assert.notNull(configuration, "configuration is required");

		// detect charset
		charset = (StringUtils.hasText(encoding) ? Charset.forName(encoding) : Charset.forName("UTF-8"));
	}

	public <T> T execute(TableCallback<T> action) {
		return execute(defaultTable, action);
	}

	/**
	 * Executes the given action against the specified table handling resource management.
	 * <p/>
	 * Application exceptions thrown by the action object get propagated to the caller (can only be unchecked). Allows for returning a 
	 * result object (typically a domain object or collection of domain objects).
	 * 
	 * @param tableName
	 * @param tableCallback
	 * @return
	 */
	public <T> T execute(String tableName, TableCallback<T> action) {
		Assert.notNull(action, "Callback object must not be null");
		Assert.notNull(tableName, "No table specified");

		HTable table = createTable(tableName);

		try {
			boolean previousFlushSetting = applyFlushSetting(table);
			T result = action.doInTable(table);
			flushIfNecessary(table, previousFlushSetting);
			return result;
		} catch (Throwable th) {
			if (th instanceof Error) {
				throw ((Error) th);
			}
			if (th instanceof RuntimeException) {
				throw ((RuntimeException) th);
			}
			throw convertHbaseAccessException((Exception) th);
		} finally {
			try {
				table.close();
			} catch (IOException ex) {
				throw convertHbaseAccessException(ex);
			}
		}
	}

	private HTable createTable(String tableName) {
		try {

			if (tableFactory != null) {
				HTableInterface table = tableFactory.createHTableInterface(configuration, tableName.getBytes(charset));
				Assert.isInstanceOf(HTable.class, table, "The table factory needs to create HTable instances");
				return (HTable) table;
			}
			return new HTable(configuration, tableName.getBytes(charset));
		} catch (Exception ex) {
			throw convertHbaseAccessException(ex);
		}
	}

	private boolean applyFlushSetting(HTable table) {
		boolean autoFlush = table.isAutoFlush();
		table.setAutoFlush(this.autoFlush);
		return autoFlush;
	}

	private void flushIfNecessary(HTable table, boolean oldFlush) throws IOException {
		// TODO: check whether we can consider or not a table scope
		table.flushCommits();
		if (table.isAutoFlush() != oldFlush) {
			table.setAutoFlush(oldFlush);
		}
	}

	public DataAccessException convertHbaseAccessException(Exception ex) {
		return HBaseUtils.convertHBaseException(ex);
	}

	public <T> T execute(String tableName, String family, final ResultsExtractor<T> action) {
		Scan scan = new Scan();
		scan.addFamily(family.getBytes(charset));
		return execute(tableName, scan, action);

	}

	public <T> T execute(String tableName, String family, String qualifier, final ResultsExtractor<T> action) {
		Scan scan = new Scan();
		scan.addColumn(family.getBytes(charset), qualifier.getBytes(charset));
		return execute(tableName, scan, action);
	}


	public <T> T execute(String tableName, final Scan scan, final ResultsExtractor<T> action) {
		return execute(tableName, new TableCallback<T>() {
			@Override
			public T doInTable(HTable htable) throws Throwable {
				ResultScanner scanner = htable.getScanner(scan);
				try {
					return action.extractData(scanner);
				} finally {
					scanner.close();
				}
			}
		});
	}

	public <T> List<T> query(String tableName, final Scan scan, final RowMapper<T> action) {
		return execute(tableName, new TableCallback<List<T>>() {
			@Override
			public List<T> doInTable(HTable htable) throws Throwable {
				ResultScanner scanner = htable.getScanner(scan);

				List<T> results = new ArrayList<T>();
				int rowNum = 0;
				try {
					for (Result result : scanner) {
						results.add(action.mapRow(result, rowNum++));
					}
					return results;
				} finally {
					scanner.close();
				}
			}
		});
	}

	/**
	 * @param autoFlush The autoFlush to set.
	 */
	public void setAutoFlush(boolean autoFlush) {
		this.autoFlush = autoFlush;
	}


	/**
	 * @param tableFactory The tableFactory to set.
	 */
	public void setTableFactory(HTableInterfaceFactory tableFactory) {
		this.tableFactory = tableFactory;
	}
}