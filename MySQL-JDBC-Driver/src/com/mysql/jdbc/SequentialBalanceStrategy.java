/*
 Copyright  2007 MySQL AB, 2008-2010 Sun Microsystems
 All rights reserved. Use is subject to license terms.

  The MySQL Connector/J is licensed under the terms of the GPL,
  like most MySQL Connectors. There are special exceptions to the
  terms and conditions of the GPL as it is applied to this software,
  see the FLOSS License Exception available on mysql.com.

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; version 2 of the
  License.

  This program is distributed in the hope that it will be useful,  
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
  02110-1301 USA
 
 */
package com.mysql.jdbc;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A balancing strategy that starts at a random point, and then advances
 * in the list (wrapping around) for each new pickConnection() call.
 * 
 * The initial point selection, and subsequent point selections are
 * blacklist-aware.
 *
 */
public class SequentialBalanceStrategy implements BalanceStrategy {
	private int currentHostIndex = -1;
	
	public SequentialBalanceStrategy() {
	}

	public void destroy() {
		// we don't have anything to clean up
	}

	public void init(Connection conn, Properties props) throws SQLException {
		// we don't have anything to initialize
	}

	public ConnectionImpl pickConnection(LoadBalancingConnectionProxy proxy,
			List<String> configuredHosts, Map<String, ConnectionImpl> liveConnections, long[] responseTimes,
			int numRetries) throws SQLException {
		int numHosts = configuredHosts.size();

		SQLException ex = null;

		Map<String, Long> blackList = proxy.getGlobalBlacklist();

		for (int attempts = 0; attempts < numRetries;) {
			if (numHosts == 1) {
				currentHostIndex = 0; // pathological case
			} else  if (currentHostIndex == -1) {
				int random = (int) Math.floor((Math.random() * numHosts));
				
				for (int i = random; i < numHosts; i++) {
					if (!blackList.containsKey(configuredHosts.get(i))) {
						currentHostIndex = i; 
						break;
					}
				}
				
				if (currentHostIndex == -1) {
					for (int i = 0; i < random; i++) {
						if (!blackList.containsKey(configuredHosts.get(i))) {
							currentHostIndex = i; 
							break;
						}
					}
				}
				
				if (currentHostIndex == -1) {
					blackList = proxy.getGlobalBlacklist(); // it may have changed
								// and the proxy returns a copy
					
					try {
						Thread.sleep(250);
					} catch (InterruptedException e) {
					}

					continue; // retry
				}
			} else {

				
				int i = currentHostIndex + 1;
				boolean foundGoodHost = false;
				
				for (; i < numHosts; i++) {
					if (!blackList.containsKey(configuredHosts.get(i))) {
						currentHostIndex = i;
						foundGoodHost = true;
						break;
					}
				}

				if (!foundGoodHost) {
					for (i = 0; i < currentHostIndex; i++) {
						if (!blackList.containsKey(configuredHosts.get(i))) {
							currentHostIndex = i;
							foundGoodHost = true;
							break;
						}
					}
				}
			
				if (!foundGoodHost) {
					blackList = proxy.getGlobalBlacklist(); // it may have changed
					// and the proxy returns a copy
		
					try {
						Thread.sleep(250);
					} catch (InterruptedException e) {
					}
			
					continue; // retry
				}
			}

			String hostPortSpec = configuredHosts.get(currentHostIndex);

			ConnectionImpl conn = liveConnections.get(hostPortSpec);

			if (conn == null) {
				try {
					conn = proxy.createConnectionForHost(hostPortSpec);
				} catch (SQLException sqlEx) {
					ex = sqlEx;

					if (sqlEx instanceof CommunicationsException
							|| "08S01".equals(sqlEx.getSQLState())) {

						proxy.addToGlobalBlacklist( hostPortSpec );

						try {
							Thread.sleep(250);
						} catch (InterruptedException e) {
						}
						
						continue;
					} else {
						throw sqlEx;
					}
				}
			}
			
			return conn;
		}

		if (ex != null) {
			throw ex;
		}

		return null; // we won't get here, compiler can't tell
	}

}