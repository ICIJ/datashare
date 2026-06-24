package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.web.WebResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class UsersInDb implements UserStore {
    private final Repository userRepository;

    @Inject
    public UsersInDb(Repository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User find(String login) {
        org.icij.datashare.user.User userInDb = userRepository.getUser(login);
        if(userInDb == null) {
            return null;
        }
        return new DatashareUser(userInDb);
    }

    @Override
    public User find(String login, String password) {
        org.icij.datashare.user.User user = userRepository.getUser(login);
        return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user): null;
    }

    @Override
    public boolean save(org.icij.datashare.user.User user) {
        return userRepository.save(user);
    }

    @Override
    public boolean delete(String login) {
        return userRepository.deleteUser(login);
    }

    @Override
    public WebResponse<org.icij.datashare.user.User> listUsers(UserFilter filter, Comparator<org.icij.datashare.user.User> sort, int from, int size) {
        Stream<org.icij.datashare.user.User> stream = userRepository.listUsers(filter).stream()
                .filter(filter::matches)
                .map(DatashareUser::new);
        if (sort != null) stream = stream.sorted(sort);
        return WebResponse.fromStream(stream, from, size);
    }

    @Override
    public List<org.icij.datashare.user.User> getUsersByIds(Set<String> ids) {
        return ids.stream()
                .map(userRepository::getUser)
                .filter(Objects::nonNull)
                .map(DatashareUser::new)
                .collect(Collectors.toList());
    }
}
