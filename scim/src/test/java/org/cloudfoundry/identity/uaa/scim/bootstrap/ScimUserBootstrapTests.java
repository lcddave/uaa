/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.scim.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.*;

import org.cloudfoundry.identity.uaa.authentication.manager.ExternalGroupAuthorizationEvent;
import org.cloudfoundry.identity.uaa.rest.jdbc.DefaultLimitSqlAdapter;
import org.cloudfoundry.identity.uaa.rest.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.endpoints.ScimUserEndpoints;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.validate.NullPasswordValidator;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.hamcrest.collection.IsArrayContainingInAnyOrder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import com.googlecode.flyway.core.Flyway;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;

/**
 * @author Luke Taylor
 * @author Dave Syer
 */
public class ScimUserBootstrapTests {

    private JdbcScimUserProvisioning db;

    private JdbcScimGroupProvisioning gdb;

    private JdbcScimGroupMembershipManager mdb;

    private ScimUserEndpoints userEndpoints;

    private EmbeddedDatabase database;
    private Flyway flyway;

    @Before
    public void setUp() {
        EmbeddedDatabaseBuilder builder = new EmbeddedDatabaseBuilder();
        database = builder.build();
        flyway = new Flyway();
        flyway.setInitVersion("1.5.2");
        flyway.setLocations("classpath:/org/cloudfoundry/identity/uaa/db/hsqldb/");
        flyway.setDataSource(database);
        flyway.migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        JdbcPagingListFactory pagingListFactory = new JdbcPagingListFactory(jdbcTemplate, new DefaultLimitSqlAdapter());
        db = new JdbcScimUserProvisioning(jdbcTemplate, pagingListFactory);
        db.setPasswordValidator(new NullPasswordValidator());
        gdb = new JdbcScimGroupProvisioning(jdbcTemplate, pagingListFactory);
        mdb = new JdbcScimGroupMembershipManager(jdbcTemplate, pagingListFactory);
        mdb.setScimUserProvisioning(db);
        mdb.setScimGroupProvisioning(gdb);
        userEndpoints = new ScimUserEndpoints();
        userEndpoints.setScimGroupMembershipManager(mdb);
        userEndpoints.setScimUserProvisioning(db);
    }

    @After
    public void shutdownDb() throws Exception {
        database.shutdown();
    }

    @Test
    public void canAddUsers() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        UaaUser mabel = new UaaUser("mabel", "password", "mabel@blah.com", "Mabel", "User");
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe, mabel));
        bootstrap.afterPropertiesSet();
        Collection<ScimUser> users = db.retrieveAll();
        assertEquals(2, users.size());
    }

    @Test
    public void canAddUserWithAuthorities() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals(3, user.getGroups().size());
    }

    @Test
    public void noOverrideByDefault() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "password", "joe@test.org", "Joel", "User");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals("Joe", user.getGivenName());
    }

    @Test
    public void canOverride() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "password", "joe@test.org", "Joel", "User");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals("Joel", user.getGivenName());
    }

    @Test
    public void canOverrideAuthorities() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read,write"));
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals(4, user.getGroups().size());
    }

    @Test
    public void canRemoveAuthorities() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid,read"));
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = joe.authorities(AuthorityUtils.commaSeparatedStringToAuthorityList("openid"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        System.err.println(jdbcTemplate.queryForList("SELECT * FROM group_membership"));
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        @SuppressWarnings("unchecked")
        Collection<Map<String, Object>> users = (Collection<Map<String, Object>>) userEndpoints.findUsers("id",
                        "id pr", "id", "ascending", 1, 100).getResources();
        assertEquals(1, users.size());

        String id = (String) users.iterator().next().get("id");
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        // uaa.user is always added
        assertEquals(2, user.getGroups().size());
    }

    @Test
    public void canUpdateUsers() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "new", "joe@test.org", "Joe", "Bloggs");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(true);
        bootstrap.afterPropertiesSet();
        Collection<ScimUser> users = db.retrieveAll();
        assertEquals(1, users.size());
        assertEquals("Bloggs", users.iterator().next().getFamilyName());
    }

    @Test
    public void failedAttemptToUpdateUsersNotFatal() throws Exception {
        UaaUser joe = new UaaUser("joe", "password", "joe@test.org", "Joe", "User");
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.afterPropertiesSet();
        joe = new UaaUser("joe", "new", "joe@test.org", "Joe", "Bloggs");
        bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(joe));
        bootstrap.setOverride(false);
        bootstrap.afterPropertiesSet();
        Collection<ScimUser> users = db.retrieveAll();
        assertEquals(1, users.size());
        assertEquals("User", users.iterator().next().getFamilyName());
    }

    @Test
    public void canAddNonExistentGroupThroughEvent() throws Exception {
        String[] externalAuthorities = new String[] {"extTest1","extTest2","extTest3"};
        String[] userAuthorities = new String[] {"usrTest1","usrTest2","usrTest3"};
        String origin = "testOrigin";
        String email = "test@test.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "";
        String externalId = null;
        String userId = new RandomValueStringGenerator().generate();
        String username = new RandomValueStringGenerator().generate();
        UaaUser user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, userId, username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();

        List<ScimUser> users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"");
        assertEquals(1, users.size());
        userId = users.get(0).getId();
        user = getUaaUser(userAuthorities, origin, email, firstName, lastName, password, externalId, userId, username);
        bootstrap.onApplicationEvent(new ExternalGroupAuthorizationEvent(user, getAuthorities(externalAuthorities)));

        users = db.query("userName eq \""+username +"\" and origin eq \""+origin+"\"");
        assertEquals(1, users.size());
        ScimUser created = users.get(0);
        Set<ScimGroup> groups = mdb.getGroupsWithMember(created.getId(),true);
        String[] expected = merge(externalAuthorities,userAuthorities);
        String[] actual = getGroupNames(groups);
        assertThat(actual, IsArrayContainingInAnyOrder.arrayContainingInAnyOrder(expected));
    }

    private UaaUser getUaaUser(String[] userAuthorities, String origin, String email, String firstName, String lastName, String password, String externalId, String userId, String username) {
        return new UaaUser(
                userId,
                username,
                password,
                email,
                getAuthorities(userAuthorities),
                firstName,
                lastName,
                new Date(),
                new Date(),
                origin,
                externalId);
    }

    @Test
    public void addUsersWithSameUsername() throws Exception {
        String origin = "testOrigin";
        String email = "test@test.org";
        String firstName = "FirstName";
        String lastName = "LastName";
        String password = "";
        String externalId = null;
        String userId = new RandomValueStringGenerator().generate();
        String username = new RandomValueStringGenerator().generate();
        UaaUser user = getUaaUser(new String[0], origin, email, firstName, lastName, password, externalId, userId, username);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(db, gdb, mdb, Arrays.asList(user));
        bootstrap.afterPropertiesSet();
        user = user.modifySource("newOrigin","");
        bootstrap.addUser(user);
        assertEquals(2, db.retrieveAll().size());
    }


    private List<GrantedAuthority> getAuthorities(String[] auth) {
        ArrayList<GrantedAuthority> result = new ArrayList<>();
        for (String s : auth) {
            result.add(new SimpleGrantedAuthority(s));
        }
        return result;
    }

    private String[] merge(String[] a, String[] b) {
        String[] result = new String[a.length+b.length];
        System.arraycopy(a,0,result,0,a.length);
        System.arraycopy(b,0,result,a.length,b.length);
        return result;
    }

    private String[] getGroupNames(Set<ScimGroup> groups) {
        String[] result = new String[groups!=null?groups.size():0];
        if (result.length==0) {
            return result;
        }
        int index = 0;
        for (ScimGroup group : groups) {
            result[index++] = group.getDisplayName();
        }
        return result;
    }
}
