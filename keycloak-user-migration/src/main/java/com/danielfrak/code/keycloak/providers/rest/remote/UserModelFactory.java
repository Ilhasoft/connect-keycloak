package com.danielfrak.code.keycloak.providers.rest.remote;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.danielfrak.code.keycloak.providers.rest.ConfigurationProperties.*;

public class UserModelFactory {

    private static final Logger LOG = Logger.getLogger(UserModelFactory.class);

    private final KeycloakSession session;
    private final ComponentModel model;

    /**
     * String format:
     * legacyRole:newRole
     */
    private final Map<String, String> roleMap;
    /**
     * String format:
     * legacyGroup:newGroup
     */
    private final Map<String, String> groupMap;

    public UserModelFactory(KeycloakSession session, ComponentModel model) {
        this.session = session;
        this.model = model;
        this.roleMap = legacyMap(model, ROLE_MAP_PROPERTY);
        this.groupMap = legacyMap(model, GROUP_MAP_PROPERTY);
    }

    /**
     * Returns a map of legacy props to new one
     */
    private Map<String, String> legacyMap(ComponentModel model, String property) {
        Map<String, String> newRoleMap = new HashMap<>();
        List<String> pairs = model.getConfig().getList(property);
        for (String pair : pairs) {
            String[] keyValue = pair.split(":");
            newRoleMap.put(keyValue[0], keyValue[1]);
        }
        return newRoleMap;
    }

    public UserModel create(LegacyUser legacyUser, RealmModel realm) {
        LOG.infof("Creating user model for: %s", legacyUser.getUsername());

        UserModel userModel;
        if (legacyUser.getId() == null) {
            userModel = session.userLocalStorage().addUser(realm, legacyUser.getUsername());
        } else {
            userModel = session.userLocalStorage().addUser(
                realm,
                legacyUser.getId(),
                legacyUser.getUsername(),
                true,
                false
            );
        }

        validateUsernamesEqual(legacyUser, userModel);

        userModel.setFederationLink(model.getId());
        userModel.setEnabled(legacyUser.isEnabled());
        userModel.setEmail(legacyUser.getEmail());
        userModel.setEmailVerified(legacyUser.isEmailVerified());
        userModel.setFirstName(legacyUser.getFirstName());
        userModel.setLastName(legacyUser.getLastName());

        if (legacyUser.getAttributes() != null) {
            legacyUser.getAttributes()
                    .forEach(userModel::setAttribute);
        }

        getRoleModels(legacyUser, realm)
                .forEach(userModel::grantRole);

        getGroupModels(legacyUser, realm)
                .forEach(userModel::joinGroup);

        return userModel;
    }

    private void validateUsernamesEqual(LegacyUser legacyUser, UserModel userModel) {
        if (!userModel.getUsername().equals(legacyUser.getUsername())) {
            throw new IllegalStateException(String.format("Local and remote users differ: [%s != %s]",
                    userModel.getUsername(),
                    legacyUser.getUsername()));
        }
    }

    private Stream<RoleModel> getRoleModels(LegacyUser legacyUser, RealmModel realm) {
        if (legacyUser.getRoles() == null) {
            return Stream.empty();
        }
        return legacyUser.getRoles().stream()
                .map(r -> getRoleModel(realm, r))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private Optional<RoleModel> getRoleModel(RealmModel realm, String role) {
        if (roleMap.containsKey(role)) {
            role = roleMap.get(role);
        } else if (!isParseEnabledFor(MIGRATE_UNMAPPED_ROLES_PROPERTY)) {
            return Optional.empty();
        }
        if (role == null || role.equals("")) {
            return Optional.empty();
        }
        RoleModel roleModel = realm.getRole(role);
        return Optional.ofNullable(roleModel);
    }

    private Stream<GroupModel> getGroupModels(LegacyUser legacyUser, RealmModel realm) {
        if (legacyUser.getGroups() == null) {
            return Stream.empty();
        }

        return legacyUser.getGroups().stream()
                .map(group -> getGroupModel(realm, group))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private Optional<GroupModel> getGroupModel(RealmModel realm, String group) {
        if (groupMap.containsKey(group)) {
            group = groupMap.get(group);
        } else if (!isParseEnabledFor(MIGRATE_UNMAPPED_GROUPS_PROPERTY)) {
            return Optional.empty();
        }
        if (group == null || group.equals("")) {
            return Optional.empty();
        }

        GroupModel realmGroup = realm.getGroupById(group);

        if (realmGroup == null) {
            realmGroup = realm.createGroup(group);
        }

        return Optional.of(realmGroup);
    }

    private boolean isParseEnabledFor(String config) {
        return Boolean.parseBoolean(model.getConfig().getFirst(config));
    }
}
